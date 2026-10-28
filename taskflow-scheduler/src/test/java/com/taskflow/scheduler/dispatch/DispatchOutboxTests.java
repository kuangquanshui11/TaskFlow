package com.taskflow.scheduler.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.model.OutboxEvent;
import com.taskflow.scheduler.dispatch.service.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"taskflow.dispatch.transport=rabbitmq", "taskflow.dispatch.max-publish-attempts=3"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class DispatchOutboxTests {
    @Autowired IRunService runs;
    @Autowired IDispatchRelayService relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean IDispatchPublisher publisher;
    @MockitoSpyBean DispatchOutboxMapper outbox;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox"); jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
    }
    @Test void runAttemptAndWaitingOutboxCommitAtomicallyAndIdempotently() {
        var request = request();
        var run = runs.insert(request); runs.insert(request);
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
        var event = outbox.find(run.attempts().getFirst().id(), false).orElseThrow();
        assertThat(event.state()).isEqualTo("WAITING");
        assertThat(event.messageJson()).isNull();
        doThrow(new IllegalStateException("injected Outbox write failure")).when(outbox).insert(any(), any());
        assertThatThrownBy(() -> runs.insert(request())).isInstanceOf(IllegalStateException.class);
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("run_attempt")).isEqualTo(1);
        assertThat(count("dispatch_outbox")).isEqualTo(1);
    }
    @Test void preparationAndLeaseRollbackTogetherAndCanRecover() {
        var run = runs.insert(request());
        doThrow(new IllegalStateException("injected preparation failure")).when(outbox).prepare(any(), anyString(), any(), any());
        assertThatThrownBy(runs::dispatchPending).isInstanceOf(IllegalStateException.class);
        assertThat(runs.selectById(run.id()).attempts().getFirst().expiresAt()).isNull();
        assertThat(outbox.find(run.attempts().getFirst().id(), false).orElseThrow().state()).isEqualTo("WAITING");
        doCallRealMethod().when(outbox).prepare(any(), anyString(), any(), any());
        runs.dispatchPending(); relay.relay();
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
    }
    @Test void publishesOutsideSqlAndBrokerConfirmationDoesNotCompleteRun() {
        var run = runs.insert(request()); runs.dispatchPending();
        var prepared = event(run);
        assertThat(prepared.state()).isEqualTo("READY");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((OutboxEvent) call.getArgument(0)).messageJson()).isEqualTo(prepared.messageJson());
            return null;
        }).when(publisher).publish(any());
        relay.relay(); makeAttemptsDue(); runs.dispatchPending(); relay.relay();
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RUNNING");
        verify(publisher, times(1)).publish(any());
    }
    @Test void crashBeforePublishRecoversExpiredClaimAndFencesOldRelay() {
        var run = runs.insert(request()); runs.dispatchPending();
        jdbc.update("UPDATE dispatch_outbox SET state='SENDING',publish_attempts=1,claim_version=1,claim_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
        var old = event(run);
        relay.relay();
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
        assertThat(event(run).claimVersion()).isEqualTo(2);
        assertThat(outbox.published(old, Instant.now())).isFalse();
        outbox.failed(old, true, "OLD_RELAY", Instant.now(), Instant.now());
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
    }
    @Test void crashAfterConfirmBeforeMarkRepublishesIdenticalEventAndLease() {
        var run = runs.insert(request()); runs.dispatchPending();
        var prepared = event(run);
        doThrow(new IllegalStateException("injected crash after confirm")).doCallRealMethod().when(outbox).published(any(), any());
        relay.relay();
        assertThat(event(run).state()).isEqualTo("SENDING");
        expireClaims(); relay.relay();
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
        assertThat(event(run).messageJson()).isEqualTo(prepared.messageJson());
        assertThat(event(run).expiresAt()).isEqualTo(prepared.expiresAt());
        assertThat(runs.selectById(run.id()).attempts()).hasSize(1);
        verify(publisher, times(2)).publish(any());
    }
    @Test void concurrentRelaysPublishOnlyClaimedEvent() throws Exception {
        var run = runs.insert(request()); runs.dispatchPending();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; }).when(publisher).publish(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(relay::relay);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try { pool.submit(relay::relay).get(5, TimeUnit.SECONDS); }
            finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(event(run).state()).isEqualTo("PUBLISHED");
        verify(publisher, times(1)).publish(any());
    }
    @Test void unavailableBrokerStopsAtLimitWithoutNewBusinessAttempt() {
        var run = runs.insert(request()); runs.dispatchPending();
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(any());
        for (int i=0; i<5; i++) { makePublicationsDue(); relay.relay(); }
        assertThat(event(run).state()).isEqualTo("FAILED");
        assertThat(event(run).publishAttempts()).isEqualTo(3);
        makeAttemptsDue(); runs.dispatchPending();
        var failed = runs.selectById(run.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.resultCode()).isEqualTo("MQ_PUBLISH_UNCONFIRMED");
        assertThat(failed.attempts()).hasSize(1);
        verify(publisher, times(3)).publish(any());
    }
    @Test void expiredLeaseCancelsUnpublishedEventAndRejectsLateResult() {
        var run = runs.insert(request()); runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        jdbc.update("UPDATE run_attempt SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        runs.dispatchPending(); relay.relay();
        assertThat(event(run).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(),run.id(),attempt.leaseVersion(),"SUCCEEDED","SUCCEEDED","late",false)))
                .isInstanceOf(com.taskflow.scheduler.execution.exception.RunException.class);
        verifyNoInteractions(publisher);
    }
    @Test void explicitBusinessFailureCreatesNewAttemptAndNewOutboxWithinSameRun() {
        var run = runs.insert(request()); runs.dispatchPending(); relay.relay();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        runs.updateResult(new Result(attempt.id(),run.id(),attempt.leaseVersion(),"FAILED","HANDLER_FAILED","retry",true));
        var waiting = runs.selectById(run.id());
        assertThat(waiting.state()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.attempts()).hasSize(2);
        assertThat(outbox.find(waiting.attempts().get(1).id(), false).orElseThrow().state()).isEqualTo("WAITING");
        assertThat(count("run_execution")).isEqualTo(1);
        assertThat(count("dispatch_outbox")).isEqualTo(2);
    }
    @Test void scheduledCreationSharesTransactionWithOutboxAndDeduplicatesOriginalPlanTime() {
        var base=request().snapshot();
        var snapshot=new Snapshot(base.jobId(),base.configVersion(),base.name(),base.handlerKey(),base.payload(),base.timeoutSeconds(),
                base.maxAttempts(),base.retryIntervalSeconds(),"CRON","*/2 * * * * *","UTC","FIRE_ONCE",true,null);
        var scheduled=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            runs.insertScheduled(snapshot,scheduled,scheduled);
            throw new IllegalStateException("plan advance failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("run_execution")).isZero(); assertThat(count("dispatch_outbox")).isZero();
        var id=tx.execute(status -> runs.insertScheduled(snapshot,scheduled,scheduled));
        UUID replay=tx.execute(status -> runs.insertScheduled(snapshot,scheduled,scheduled.plusSeconds(5)));
        assertThat(replay).isEqualTo(id);
        assertThat(count("run_execution")).isEqualTo(1); assertThat(count("dispatch_outbox")).isEqualTo(1);
    }
    private Create request() {
        return new Create(UUID.randomUUID(),new Snapshot(7,2,"mq-test","demoSuccess",json.readTree("{\"n\":1}"),
                60,3,5,"MANUAL",null,"UTC","SKIP",false,null));
    }
    private OutboxEvent event(com.taskflow.scheduler.execution.model.RunRecords.RunView run) { return outbox.find(run.attempts().getFirst().id(),false).orElseThrow(); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void makePublicationsDue() { jdbc.update("UPDATE dispatch_outbox SET next_publish_at=?",Timestamp.from(Instant.now().minusSeconds(1))); }
    private void expireClaims() { jdbc.update("UPDATE dispatch_outbox SET claim_until=?",Timestamp.from(Instant.now().minusSeconds(1))); }
    private void makeAttemptsDue() { jdbc.update("UPDATE run_attempt SET next_dispatch_at=?",Timestamp.from(Instant.now().minusSeconds(1))); }
}
