package com.taskflow.scheduler.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.IDispatchPublisher;
import com.taskflow.scheduler.dispatch.service.IDispatchRelayService;
import com.taskflow.scheduler.dispatch.service.impl.DispatchRelayServiceImpl;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.RunView;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.execution.service.impl.RunServiceImpl;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles(resolver = DispatchFairnessTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = RunStateConcurrencyTests.DatabaseGuard.class)
class DispatchFairnessTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired RunProperties runProperties;
    @Autowired DispatchProperties dispatchProperties;
    @MockitoSpyBean RunDataMapper mapper;
    @MockitoSpyBean DispatchOutboxMapper outbox;
    @MockitoBean WorkerClient worker;
    @MockitoBean IWorkerNodeService nodes;
    @MockitoBean IDispatchPublisher publisher;

    public static class DatabaseProfiles implements ActiveProfilesResolver {
        @Override public String[] resolve(Class<?> testClass) {
            return Stream.concat(Arrays.stream(new RunStateConcurrencyTests.DatabaseProfiles().resolve(testClass)),
                    Stream.of("test-dispatch-fairness")).toArray(String[]::new);
        }
    }

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox");
        jdbc.update("DELETE FROM run_attempt");
        jdbc.update("DELETE FROM run_execution");
        when(nodes.canDispatch("demoSuccess")).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "rabbitmq"})
    void moreThanOneBatchOfBlockedAttemptsYieldsToValidWorkAndRecoversOriginalAttempt(String transport) {
        var service = service(transport);
        var blocked = new ArrayList<RunView>();
        for (int i = 0; i < 250; i++) blocked.add(service.insert(request("removedHandler")));
        var valid = service.insert(request("demoSuccess"));
        var validAttempt = valid.attempts().getFirst();
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE id<>?", past(), validAttempt.id().toString());
        var beforeScan = Instant.now();
        for (int i = 0; i < 4; i++) service.dispatchPending();

        var accepted = service.selectById(valid.id());
        assertThat(accepted.attempts().getFirst().dispatchCount()).isEqualTo(1);
        assertThat(accepted.attempts().getFirst().expiresAt()).isNotNull();
        for (var run : blocked) {
            var attempt = mapper.attempt(run.attempts().getFirst().id()).orElseThrow();
            assertThat(attempt.state()).isEqualTo("READY");
            assertThat(attempt.nextDispatchAt()).isAfter(beforeScan);
            assertThat(attempt.dispatchCount()).isZero();
            assertThat(attempt.expiresAt()).isNull();
            assertThat(attempt.leaseVersion()).isEqualTo(run.attempts().getFirst().leaseVersion());
            if (transport.equals("rabbitmq"))
                assertThat(outbox.find(attempt.id(), false).orElseThrow().state()).isEqualTo("WAITING");
        }
        if (transport.equals("http")) verify(worker, times(1)).dispatch(any());
        else {
            relay().relay();
            assertThat(outbox.find(validAttempt.id(), false).orElseThrow().state()).isEqualTo("PUBLISHED");
            verify(publisher, times(1)).publish(any());
        }

        var first = blocked.getFirst();
        var firstAttempt = first.attempts().getFirst();
        when(nodes.canDispatch("removedHandler")).thenReturn(true);
        // 重新构造 Service 模拟进程恢复；未到检查时间仍不得抢跑。
        var restarted = service(transport);
        restarted.dispatchPending();
        assertThat(mapper.attempt(firstAttempt.id()).orElseThrow().dispatchCount()).isZero();
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE id=?", past(), firstAttempt.id().toString());
        restarted.dispatchPending();
        var restored = restarted.selectById(first.id());
        assertThat(restored.attempts()).hasSize(1);
        assertThat(restored.attempts().getFirst().id()).isEqualTo(firstAttempt.id());
        assertThat(restored.attempts().getFirst().leaseVersion()).isEqualTo(firstAttempt.leaseVersion());
        assertThat(restored.attempts().getFirst().dispatchCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "SENDING"})
    void blockedRelayBatchYieldsAndExpiredSendingClaimsRespectNextCheck(String state) {
        var service = service("rabbitmq");
        when(nodes.canDispatch("removedHandler")).thenReturn(true);
        var blocked = new ArrayList<RunView>();
        for (int i = 0; i < 100; i++) blocked.add(service.insert(request("removedHandler")));
        var valid = service.insert(request("demoSuccess"));
        service.dispatchPending(); service.dispatchPending();
        jdbc.update("UPDATE dispatch_outbox SET next_publish_at=? WHERE event_id<>?", past(), valid.attempts().getFirst().id().toString());
        if (state.equals("SENDING"))
            jdbc.update("UPDATE dispatch_outbox SET state='SENDING',publish_attempts=1,claim_version=7,claim_until=? WHERE event_id<>?",
                    past(), valid.attempts().getFirst().id().toString());
        var firstId = blocked.getFirst().attempts().getFirst().id();
        var original = outbox.find(firstId, false).orElseThrow();
        when(nodes.canDispatch("removedHandler")).thenReturn(false);
        var beforeScan = Instant.now();
        relay().relay();
        relay().relay();
        assertThat(outbox.find(valid.attempts().getFirst().id(), false).orElseThrow().state()).isEqualTo("PUBLISHED");
        verify(publisher, times(1)).publish(any());
        for (var run : blocked) {
            var event = outbox.find(run.attempts().getFirst().id(), false).orElseThrow();
            assertThat(event.state()).isEqualTo(state);
            assertThat(event.nextPublishAt()).isAfter(beforeScan);
            assertThat(event.publishAttempts()).isEqualTo(state.equals("SENDING") ? 1 : 0);
            assertThat(event.claimVersion()).isEqualTo(state.equals("SENDING") ? 7 : 0);
        }
        assertThat(outbox.due(Instant.now())).doesNotContain(firstId);
        var waiting = outbox.find(firstId, false).orElseThrow();
        assertThat(waiting.claimUntil()).isEqualTo(original.claimUntil());
        assertThat(waiting.messageJson()).isEqualTo(original.messageJson());
        assertThat(waiting.expiresAt()).isEqualTo(original.expiresAt());
        if (state.equals("SENDING")) assertThat(outbox.published(original, Instant.now())).isFalse();

        when(nodes.canDispatch("removedHandler")).thenReturn(true);
        relay().relay();
        verify(publisher, times(1)).publish(any());
        jdbc.update("UPDATE dispatch_outbox SET next_publish_at=? WHERE event_id=?", past(), firstId.toString());
        relay().relay();
        var restored = outbox.find(firstId, false).orElseThrow();
        assertThat(restored.state()).isEqualTo("PUBLISHED");
        assertThat(restored.publishAttempts()).isEqualTo(original.publishAttempts() + 1);
        assertThat(restored.claimVersion()).isEqualTo(original.claimVersion() + 1);
        assertThat(restored.messageJson()).isEqualTo(original.messageJson());
        assertThat(restored.expiresAt()).isEqualTo(original.expiresAt());
        verify(publisher, times(2)).publish(any());
    }

    @Test void preparedAttemptStillExpiresWhilePublicationIsDeferred() {
        var service = service("rabbitmq");
        var run = service.insert(request("demoSuccess"));
        var id = run.attempts().getFirst().id();
        service.dispatchPending();
        when(nodes.canDispatch("demoSuccess")).thenReturn(false);
        relay().relay();
        assertThat(outbox.find(id, false).orElseThrow().nextPublishAt()).isAfter(Instant.now());
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?", past(), id.toString());
        service.dispatchPending();
        var after = service.selectById(run.id());
        assertThat(after.state()).isEqualTo("FAILED");
        assertThat(after.attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        assertThat(outbox.find(id, false).orElseThrow().state()).isEqualTo("CANCELLED");
        verifyNoInteractions(publisher);
    }

    @Test void httpRedispatchDeferralPreservesExistingDeadlineAndBudget() {
        var service = service("http");
        doThrow(new RunException(503, "OFFLINE", "injected transport failure")).when(worker).dispatch(any());
        var run = service.insert(request("demoSuccess"));
        var id = run.attempts().getFirst().id();
        service.dispatchPending();
        var sent = mapper.attempt(id).orElseThrow();
        assertThat(sent.state()).isEqualTo("READY");
        assertThat(sent.dispatchCount()).isEqualTo(1);
        when(nodes.canDispatch("demoSuccess")).thenReturn(false);
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE id=?", past(), id.toString());
        service.dispatchPending();
        var waiting = mapper.attempt(id).orElseThrow();
        assertThat(waiting.expiresAt()).isEqualTo(sent.expiresAt());
        assertThat(waiting.dispatchCount()).isEqualTo(1);
        assertThat(waiting.leaseVersion()).isEqualTo(sent.leaseVersion());
        assertThat(waiting.nextDispatchAt()).isAfter(Instant.now());
        verify(worker, times(1)).dispatch(any());
        when(nodes.canDispatch("demoSuccess")).thenReturn(true);
        doNothing().when(worker).dispatch(any());
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE id=?", past(), id.toString());
        service.dispatchPending();
        var resumed = service.selectById(run.id());
        assertThat(resumed.state()).isEqualTo("RUNNING");
        assertThat(resumed.attempts()).hasSize(1);
        assertThat(resumed.attempts().getFirst().expiresAt()).isEqualTo(sent.expiresAt());
        assertThat(resumed.attempts().getFirst().dispatchCount()).isEqualTo(2);
    }

    @Test void failedAttemptDeferralRollsBackScheduleWithoutClaimingExecution() {
        var service = service("http");
        var run = service.insert(request("removedHandler"));
        var original = mapper.attempt(run.attempts().getFirst().id()).orElseThrow();
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after deferral SQL");
        }).when(mapper).deferDispatch(any(), any(), any());
        assertThatThrownBy(service::dispatchPending).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.attempt(original.id()).orElseThrow()).isEqualTo(original);
        verifyNoInteractions(worker, publisher);
    }

    @Test void failedPublicationDeferralRollsBackScheduleAndClaim() {
        var service = service("rabbitmq");
        var run = service.insert(request("demoSuccess"));
        var id = run.attempts().getFirst().id();
        service.dispatchPending();
        var original = outbox.find(id, false).orElseThrow();
        when(nodes.canDispatch("demoSuccess")).thenReturn(false);
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after deferral SQL");
        }).when(outbox).deferPublication(any(), any(), any());
        relay().relay();
        assertThat(outbox.find(id, false).orElseThrow()).isEqualTo(original);
        verifyNoInteractions(publisher);
    }

    @Test void deferralCannotOverrideLiveClaimNewerClaimOrCancellation() {
        var service = service("rabbitmq");
        var run = service.insert(request("demoSuccess"));
        var id = run.attempts().getFirst().id();
        service.dispatchPending();
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            var original = outbox.find(id, true).orElseThrow();
            var now = Instant.now();
            var next = now.plus(dispatchProperties.nodeRecheckDelay());
            assertThat(outbox.deferPublication(original, next, now)).isTrue();
            // 同一份过期候选不能反复推迟已经安排好的检查时间。
            assertThat(outbox.deferPublication(original, next.plusSeconds(1), now)).isFalse();
            jdbc.update("UPDATE dispatch_outbox SET next_publish_at=? WHERE event_id=?", past(), id.toString());
            outbox.claim(id, now.plusSeconds(30));
            var live = outbox.find(id, true).orElseThrow();
            assertThat(outbox.deferPublication(original, next, now)).isFalse();
            assertThat(outbox.deferPublication(live, next, now)).isFalse();
            assertThat(outbox.find(id, false).orElseThrow()).isEqualTo(live);
            outbox.cancel(id);
            assertThat(outbox.deferPublication(live, next, now)).isFalse();
        });
        assertThat(outbox.find(id, false).orElseThrow().state()).isEqualTo("CANCELLED");
    }

    private IRunService service(String transport) {
        return new RunServiceImpl(mapper, worker, runProperties, json, manager, properties(transport), outbox, nodes);
    }

    private IDispatchRelayService relay() {
        return new DispatchRelayServiceImpl(outbox, publisher, properties("rabbitmq"), manager, nodes, json);
    }

    private DispatchProperties properties(String transport) {
        var p = dispatchProperties;
        return new DispatchProperties(transport, p.exchange(), p.queue(), p.routingKey(), p.deadLetterExchange(),
                p.deadLetterQueue(), p.deadLetterRoutingKey(), p.confirmTimeout(), p.claimLease(), p.retryDelay(),
                p.maxRetryDelay(), p.maxPublishAttempts(), false, p.nodeRecheckDelay());
    }

    private Create request(String handler) {
        return new Create(UUID.randomUUID(), new Snapshot(7, 1, "dispatch fairness", handler, json.readTree("{}"),
                300, 3, 5, "MANUAL", null, "UTC", "SKIP", false, null));
    }

    private Timestamp past() { return Timestamp.from(Instant.now().minusSeconds(10)); }
}
