package com.taskflow.scheduler.execution;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.RunView;
import com.taskflow.scheduler.execution.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(resolver = RunStateConcurrencyTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = RunStateConcurrencyTests.DatabaseGuard.class)
class ExecutionLeaseTests {
    @Autowired IRunService runs;
    @Autowired IExecutionLeaseService leases;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;
    @Value("${local.server.port}") int port;
    @Value("${taskflow.nodes.target-worker-id}") String workerId;
    @MockitoBean WorkerClient worker;
    @MockitoSpyBean RunDataMapper mapper;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox"); jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
    }
    @Test void bindsWorkerAndSessionAndRenewalKeepsMessageAndVersionUnchanged() {
        var command = new java.util.ArrayList<Dispatch>();
        doAnswer(call -> { command.add(call.getArgument(0)); return null; }).when(worker).dispatch(any());
        var run = dispatched(120);
        var request = request(run);
        var first = leases.updateLease(request);
        jdbc.update("UPDATE run_attempt SET lease_until=? WHERE id=?",
                Timestamp.from(first.leaseUntil().minusSeconds(1)), request.attemptId().toString());
        var second = leases.updateLease(request);
        var after = runs.selectById(run.id()).attempts().getFirst();
        assertThat(after.ownerWorkerId()).isEqualTo(workerId);
        assertThat(after.ownerSessionId()).isEqualTo(request.sessionId());
        assertThat(second.leaseUntil()).isAfterOrEqualTo(first.leaseUntil()).isBefore(first.hardDeadline());
        assertThat(second.executionDeadline()).isEqualTo(first.executionDeadline());
        assertThat(after.leaseVersion()).isEqualTo(command.getFirst().leaseVersion());
        assertThat(after.expiresAt()).isEqualTo(command.getFirst().expiresAt());
        assertThat(runs.selectById(run.id()).attempts()).hasSize(1);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM scheduler_attempt_event
                WHERE run_id=? AND attempt_id=? AND worker_id=? AND lease_version=? AND deadline_at IS NOT NULL ORDER BY id
                """, String.class, run.id().toString(), request.attemptId().toString(), workerId, request.leaseVersion()))
                .containsExactly("HTTP_DISPATCH_CLAIMED", "LEASE_ACQUIRED", "LEASE_RENEWED");
    }
    @Test void wrongWorkerSessionRunAndVersionCannotRenew() {
        var run = dispatched(120); var request = request(run); leases.updateLease(request);
        var before = runs.selectById(run.id()).attempts().getFirst();
        for (var invalid : List.of(
                new LeaseRequest(request.attemptId(), request.runId(), request.leaseVersion(), "another-worker", request.sessionId()),
                new LeaseRequest(request.attemptId(), request.runId(), request.leaseVersion(), workerId, UUID.randomUUID()),
                new LeaseRequest(request.attemptId(), UUID.randomUUID(), request.leaseVersion(), workerId, request.sessionId()),
                new LeaseRequest(request.attemptId(), request.runId(), request.leaseVersion() + 1, workerId, request.sessionId()))) {
            assertStale(() -> leases.updateLease(invalid));
        }
        assertThat(runs.selectById(run.id()).attempts().getFirst()).isEqualTo(before);
    }
    @Test void expiredLeaseCannotBeRevivedOrCompleteEvenBeforeTimeoutScan() {
        var run = dispatched(120); var request = request(run); leases.updateLease(request);
        jdbc.update("UPDATE run_attempt SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), request.attemptId().toString());
        assertStale(() -> leases.updateLease(request));
        assertStale(() -> runs.updateResult(success(request)));
        runs.dispatchPending();
        var expired = runs.selectById(run.id());
        assertThat(expired.attempts()).hasSize(1);
        assertThat(expired.attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        assertStale(() -> leases.updateLease(request));
    }
    @Test void hardDeadlineBoundsEveryRenewalAndResultsCanArriveDuringGrace() {
        var run = dispatched(120); var request = request(run);
        var hardDeadline = Instant.now().plusSeconds(12).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?", Timestamp.from(hardDeadline), request.attemptId().toString());
        var first = leases.updateLease(request);
        assertThat(first.leaseUntil()).isEqualTo(hardDeadline);
        // 固定快照的开始时间前移，模拟 Handler 已结束但仍处于结果宽限期。
        jdbc.update("UPDATE run_attempt SET started_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(121)), request.attemptId().toString());
        assertThat(leases.updateLease(request).executionDeadline()).isBefore(Instant.now());
        runs.updateResult(success(request));
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertStale(() -> leases.updateLease(request));
        runs.updateResult(success(request));
    }
    @Test void firstAcquisitionAfterExecutionDeadlineAndUnclaimedWorkAreRejected() {
        var run = dispatched(1); var request = request(run);
        jdbc.update("UPDATE run_attempt SET started_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(2)), request.attemptId().toString());
        assertStale(() -> leases.updateLease(request));
        var unclaimed = runs.insert(create(120));
        assertStale(() -> leases.updateLease(request(unclaimed)));
    }
    @Test void staleTimeoutCandidateIsRecheckedUnderLocksAfterRenewal() {
        var run = dispatched(120); var request = request(run); leases.updateLease(request);
        doReturn(List.of(request.attemptId())).when(mapper).expired(any());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RUNNING");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 503})
    void acquiredLeaseWinsOverDispatchFailureEvenWhenDispatchBudgetIsExhausted(int remoteStatus) {
        var run = runs.insert(create(120));
        jdbc.update("UPDATE run_attempt SET dispatch_count=4 WHERE id=?", run.attempts().getFirst().id().toString());
        doAnswer(call -> {
            Dispatch dispatch = call.getArgument(0);
            leases.updateLease(new LeaseRequest(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(), workerId, UUID.randomUUID()));
            throw new RunException(remoteStatus, "UNCONFIRMED", "response lost after acquisition");
        }).when(worker).dispatch(any());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RUNNING");
        assertThat(runs.selectById(run.id()).attempts().getFirst().dispatchCount()).isEqualTo(5);
    }
    @Test void sqlConditionRejectsExpiredWrongOwnerAndTerminalRowsWithStaleInputs() {
        var run = dispatched(120); var request = request(run); leases.updateLease(request);
        var stale = mapper.attempt(request.attemptId()).orElseThrow();
        assertThat(mapper.updateLease(stale, workerId, UUID.randomUUID(), Instant.now().plusSeconds(10), Instant.now())).isFalse();
        jdbc.update("UPDATE run_attempt SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), request.attemptId().toString());
        assertThat(mapper.updateLease(stale, workerId, request.sessionId(), Instant.now().plusSeconds(10), Instant.now())).isFalse();
        jdbc.update("UPDATE run_attempt SET lease_until=?,state='SUCCEEDED' WHERE id=?", Timestamp.from(Instant.now().plusSeconds(10)), request.attemptId().toString());
        assertThat(mapper.updateLease(stale, workerId, request.sessionId(), Instant.now().plusSeconds(10), Instant.now())).isFalse();
    }
    @Test void completedResultWinsAgainstRenewalWaitingOnRunLock() throws Exception {
        var run = dispatched(120); var request = request(run); leases.updateLease(request);
        var read = new CountDownLatch(1);
        var renewalThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call -> {
            var answer = call.callRealMethod();
            if (Thread.currentThread() == renewalThread.get()) read.countDown();
            return answer;
        }).when(mapper).attempt(request.attemptId());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                mapper.lock(run.id());
                pending.set(pool.submit(() -> { renewalThread.set(Thread.currentThread()); return leases.updateLease(request); }));
                try { assertThat(read.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                runs.updateResult(success(request));
            });
            assertThatThrownBy(() -> pending.get().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(RunException.class);
        }
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void httpRenewalUsesReportAuthenticationAndValidatesArguments() throws Exception {
        var run = dispatched(120); var request = request(run);
        assertThat(post(request, null).statusCode()).isEqualTo(401);
        assertThat(post(request, "test-control-token").statusCode()).isEqualTo(401);
        assertThat(post(request, "test-report-token").statusCode()).isEqualTo(200);
        assertThat(post(new LeaseRequest(request.attemptId(), request.runId(), 0, workerId, request.sessionId()), "test-report-token").statusCode()).isEqualTo(400);
    }
    private RunView dispatched(int timeout) { var run = runs.insert(create(timeout)); runs.dispatchPending(); return runs.selectById(run.id()); }
    private Create create(int timeout) { return new Create(UUID.randomUUID(), new Snapshot(7, 1, "lease-test", "demoSuccess", json.readTree("{}"), timeout, 3, 1, "MANUAL", null, "UTC", "SKIP", false, null)); }
    private LeaseRequest request(RunView run) { var a = run.attempts().getFirst(); return new LeaseRequest(a.id(), run.id(), a.leaseVersion(), workerId, UUID.randomUUID()); }
    private Result success(LeaseRequest request) { return new Result(request.attemptId(), request.runId(), request.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "done", false, request.workerId(), request.sessionId()); }
    private void assertStale(Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(RunException.class, failure -> assertThat(failure.status()).isEqualTo(409)); }
    private HttpResponse<String> post(LeaseRequest request, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/attempts/updateLease"))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("X-Taskflow-Token", token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
