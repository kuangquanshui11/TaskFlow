package com.taskflow.scheduler.execution;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.model.RunRecords.RunView;
import com.taskflow.scheduler.execution.service.*;
import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;
import com.taskflow.scheduler.node.exception.WorkerNodeException;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"taskflow.dispatch.transport=rabbitmq", "taskflow.nodes.dispatch-gating-enabled=true",
        "taskflow.execution.retry-on-lease-loss=true"})
@ActiveProfiles(resolver = com.taskflow.scheduler.execution.RunStateConcurrencyTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = com.taskflow.scheduler.execution.RunStateConcurrencyTests.DatabaseGuard.class)
class WorkerClusterDispatchTests {
    @Autowired IWorkerNodeService nodes;
    @Autowired IRunService runs;
    @Autowired IExecutionLeaseService leases;
    @Autowired IDispatchRelayService relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean DispatchOutboxMapper outbox;
    @MockitoBean IDispatchPublisher publisher;
    @MockitoBean(name = "nodeClock") Clock clock;
    private Instant base;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox"); jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
        jdbc.update("DELETE FROM worker_node_session"); jdbc.update("DELETE FROM worker_node");
        base = Instant.now(); when(clock.instant()).thenReturn(base);
    }
    @Test void twoNodesShareNewAttemptsAndOutstandingBindingsBoundUnobservedLoad() {
        register("worker-a", "demoSuccess"); register("worker-b", "demoSuccess");
        var created = new ArrayList<RunView>();
        for (int i = 0; i < 10; i++) created.add(create("demoSuccess", 1));
        runs.dispatchPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE target_worker_id='worker-a'", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE target_worker_id='worker-b'", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE target_worker_id IS NULL AND expires_at IS NULL AND dispatch_count=0", Long.class)).isEqualTo(2);
        for (var run : created) {
            var attempt = runs.selectById(run.id()).attempts().getFirst();
            var event = outbox.find(attempt.id(), false).orElseThrow();
            if (attempt.targetWorkerId() != null) {
                assertThat(event.target().workerId()).isEqualTo(attempt.targetWorkerId());
                assertThat(event.target().queue()).isEqualTo("taskflow." + attempt.targetWorkerId() + ".v1");
            } else assertThat(event.state()).isEqualTo("WAITING");
        }
    }
    @Test void handlerAndHeartbeatCapacitySelectOnlyQualifiedNodes() {
        register("worker-a", "demoSuccess"); var busy = register("worker-b", "demoSlow");
        nodes.heartbeat(new Heartbeat("worker-b", busy.sessionId(), 1, 1, 2, 2));
        var success = create("demoSuccess", 1); var slow = create("demoSlow", 1); var unknown = create("missing", 1);
        runs.dispatchPending();
        assertThat(runs.selectById(success.id()).attempts().getFirst().targetWorkerId()).isEqualTo("worker-a");
        assertThat(runs.selectById(slow.id()).attempts().getFirst().expiresAt()).isNull();
        assertThat(runs.selectById(unknown.id()).attempts().getFirst().expiresAt()).isNull();
        nodes.heartbeat(new Heartbeat("worker-b", busy.sessionId(), 1, 2, 0, 0)); dueAttempts(); runs.dispatchPending();
        assertThat(runs.selectById(slow.id()).attempts().getFirst().targetWorkerId()).isEqualTo("worker-b");
    }
    @Test void publicationRetryUsesOriginalRouteWhenOtherNodeHasLowerLoad() {
        var a = register("worker-a", "demoSuccess"); var run = create("demoSuccess", 1); runs.dispatchPending();
        var original = outbox.find(run.attempts().getFirst().id(), false).orElseThrow();
        doThrow(new IllegalStateException("confirmation lost")).doNothing().when(publisher).publish(any());
        relay.relay();
        register("worker-b", "demoSuccess"); nodes.heartbeat(new Heartbeat("worker-a", a.sessionId(), 1, 1, 2, 1));
        var another = create("demoSuccess", 1); runs.dispatchPending();
        assertThat(runs.selectById(another.id()).attempts().getFirst().targetWorkerId()).isEqualTo("worker-b");
        jdbc.update("UPDATE dispatch_outbox SET next_publish_at=? WHERE event_id=?", past(), original.eventId().toString());
        relay.relay();
        var retried = outbox.find(original.eventId(), false).orElseThrow();
        assertThat(retried.state()).isEqualTo("PUBLISHED");
        assertThat(retried.target()).isEqualTo(original.target());
        assertThat(retried.messageJson()).isEqualTo(original.messageJson());
        assertThat(retried.expiresAt()).isEqualTo(original.expiresAt());
        verify(publisher, times(2)).publish(argThat(e -> e.eventId().equals(original.eventId()) && e.target().equals(original.target())));
    }
    @Test void offlineTargetIsDeferredAndNeverRedirectedToAnotherOnlineNode() {
        register("worker-a", "demoSuccess"); var run = create("demoSuccess", 1); runs.dispatchPending();
        var original = outbox.find(run.attempts().getFirst().id(), false).orElseThrow();
        when(clock.instant()).thenReturn(base.plusSeconds(31)); register("worker-b", "demoSuccess");
        relay.relay();
        var deferred = outbox.find(original.eventId(), false).orElseThrow();
        assertThat(deferred.target()).isEqualTo(original.target()); assertThat(deferred.publishAttempts()).isZero();
        assertThat(deferred.state()).isEqualTo("READY"); verifyNoInteractions(publisher);
    }
    @Test void leaseLossCreatesNewAttemptOnAnotherNodeAndFencesOldOwner() {
        register("worker-a", "demoSuccess"); var run = create("demoSuccess", 2); runs.dispatchPending();
        var first = runs.selectById(run.id()).attempts().getFirst();
        var owner = new LeaseRequest(first.id(), run.id(), first.leaseVersion(), "worker-a", UUID.randomUUID());
        leases.updateLease(owner);
        jdbc.update("UPDATE run_attempt SET lease_until=? WHERE id=?", past(), first.id().toString());
        when(clock.instant()).thenReturn(base.plusSeconds(31)); register("worker-b", "demoSuccess");
        runs.dispatchPending();
        var retry = runs.selectById(run.id()); assertThat(retry.state()).isEqualTo("RETRY_WAIT"); assertThat(retry.attempts()).hasSize(2);
        assertThat(retry.attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        assertThat(outbox.find(first.id(), false).orElseThrow().state()).isEqualTo("CANCELLED");
        dueAttempts(); runs.dispatchPending();
        var second = runs.selectById(run.id()).attempts().getLast();
        assertThat(second.targetWorkerId()).isEqualTo("worker-b"); assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.leaseVersion()).isGreaterThan(first.leaseVersion());
        assertStale(() -> leases.updateLease(owner));
        assertStale(() -> runs.updateResult(new Result(first.id(), run.id(), first.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "late", false, owner.workerId(), owner.sessionId())));
        assertStale(() -> leases.updateLease(new LeaseRequest(second.id(), run.id(), second.leaseVersion(), "worker-a", UUID.randomUUID())));
        var nextOwner = new LeaseRequest(second.id(), run.id(), second.leaseVersion(), "worker-b", UUID.randomUUID()); leases.updateLease(nextOwner);
        runs.updateResult(new Result(second.id(), run.id(), second.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "done", false, nextOwner.workerId(), nextOwner.sessionId()));
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void retryBudgetTerminatesRepeatedLeaseLoss() {
        register("worker-a", "demoSuccess"); var run = create("demoSuccess", 2); runs.dispatchPending();
        for (int i = 0; i < 2; i++) {
            jdbc.update("UPDATE run_attempt SET expires_at=? WHERE run_id=? AND state IN ('DISPATCHING','RUNNING')", past(), run.id().toString());
            runs.dispatchPending(); dueAttempts(); runs.dispatchPending();
        }
        var failed = runs.selectById(run.id()); assertThat(failed.state()).isEqualTo("FAILED"); assertThat(failed.attempts()).hasSize(2);
        assertThat(failed.attempts()).allMatch(a -> a.state().equals("TIMED_OUT"));
    }
    @Test void resultMustMatchTargetAndAcquiredExecutionSessionIncludingIdempotentReplay() {
        register("worker-a", "demoSuccess"); var run = create("demoSuccess", 1); runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        var session = UUID.randomUUID();
        assertStale(() -> leases.updateLease(new LeaseRequest(attempt.id(), run.id(), 1, "worker-b", session)));
        leases.updateLease(new LeaseRequest(attempt.id(), run.id(), 1, "worker-a", session));
        for (var wrong : List.of(
                new Result(attempt.id(), run.id(), 1, "FAILED", "LEASE_LOST", "wrong node", false, "worker-b", session),
                new Result(attempt.id(), run.id(), 1, "FAILED", "WORKER_RESTARTED", "wrong session", false, "worker-a", UUID.randomUUID())))
            assertStale(() -> runs.updateResult(wrong));
        var success = new Result(attempt.id(), run.id(), 1, "SUCCEEDED", "SUCCEEDED", "done", false, "worker-a", session);
        runs.updateResult(success); runs.updateResult(success);
        assertStale(() -> runs.updateResult(new Result(attempt.id(), run.id(), 1, "SUCCEEDED", "SUCCEEDED", "done", false, "worker-a", UUID.randomUUID())));
    }
    @Test void routeOwnershipAndActiveBindingsPreventAccidentalFanoutOrRouteChanges() {
        register("worker-a", "demoSuccess");
        assertThatThrownBy(() -> nodes.register(new Registration("worker-b", "b", UUID.randomUUID(), List.of("demoSuccess"), 2, 4,
                "rabbitmq", "taskflow", "taskflow.dispatch.v1", "another.queue", "worker-a"))).isInstanceOf(WorkerNodeException.class);
        create("demoSuccess", 1); runs.dispatchPending(); when(clock.instant()).thenReturn(base.plusSeconds(31));
        assertThatThrownBy(() -> nodes.register(new Registration("worker-a", "a", UUID.randomUUID(), List.of("demoSuccess"), 2, 4,
                "rabbitmq", "taskflow", "taskflow.dispatch.v1", "new.queue", "new.route"))).isInstanceOf(WorkerNodeException.class);
        // 使用原 Inbox、能力和路由的新进程仍能注册；执行租约会话不会自动继承。
        assertThat(register("worker-a", "demoSuccess").generation()).isEqualTo(2);
    }
    @Test void targetAndOutboxPreparationRollbackTogether() {
        register("worker-a", "demoSuccess"); var run = create("demoSuccess", 1);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("prepare failed"); })
                .when(outbox).prepare(any(), anyString(), any(), any(), any());
        assertThatThrownBy(runs::dispatchPending).isInstanceOf(IllegalStateException.class);
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        assertThat(attempt.targetWorkerId()).isNull(); assertThat(attempt.expiresAt()).isNull(); assertThat(attempt.dispatchCount()).isZero();
        var event = outbox.find(attempt.id(), false).orElseThrow(); assertThat(event.target()).isNull(); assertThat(event.state()).isEqualTo("WAITING");
    }
    private Acknowledgement register(String id, String handler) {
        return nodes.register(new Registration(id, id, UUID.randomUUID(), List.of(handler), 2, 4, "rabbitmq",
                "taskflow", "taskflow.dispatch.v1", "taskflow." + id + ".v1", id));
    }
    private RunView create(String handler, int maxAttempts) {
        return runs.insert(new Create(UUID.randomUUID(), new Snapshot(7, 1, "cluster", handler, json.readTree("{}"),
                60, maxAttempts, 1, "MANUAL", null, "UTC", "SKIP", false, null)));
    }
    private void dueAttempts() { jdbc.update("UPDATE run_attempt SET next_dispatch_at=?", past()); }
    private Timestamp past() { return Timestamp.from(Instant.now().minusSeconds(1)); }
    private void assertStale(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RunException.class, e -> assertThat(e.status()).isEqualTo(409));
    }
}
