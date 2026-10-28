package com.taskflow.scheduler.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.Attempt;
import com.taskflow.scheduler.execution.model.RunRecords.RunView;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.execution.service.impl.RunServiceImpl;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles(resolver = RunStateConcurrencyTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = RunStateConcurrencyTests.DatabaseGuard.class)
class RunStateConcurrencyTests {
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired RunProperties runProperties;
    @Autowired DispatchProperties dispatchProperties;
    @Autowired DispatchOutboxMapper outbox;
    @Autowired IWorkerNodeService nodes;
    @MockitoBean WorkerClient worker;
    @MockitoSpyBean RunDataMapper mapper;

    public static class DatabaseProfiles implements ActiveProfilesResolver {
        @Override public String[] resolve(Class<?> testClass) {
            boolean mysql = System.getenv("TASKFLOW_RUN_STATE_TEST_DB_URL") != null
                    || System.getenv("TASKFLOW_RUN_STATE_TEST_DB_SCHEMA") != null;
            return mysql ? new String[]{"test", "test-run-state-mysql"} : new String[]{"test"};
        }
    }

    public static class DatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            var url = context.getEnvironment().getProperty("spring.datasource.url", "");
            // 在数据源和 Flyway 创建前拒绝业务库；MySQL 测试只接收回环地址和专用 schema。
            if (!url.startsWith("jdbc:h2:mem:taskflow-scheduler-")
                    && !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/taskflow_it_run_state_[a-z0-9_]+(?:\\?.*)?"))
                throw new IllegalStateException("Run 状态测试只能使用隔离 H2 或专用本机 MySQL 测试库");
        }
    }

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox");
        jdbc.update("DELETE FROM run_attempt");
        jdbc.update("DELETE FROM run_execution");
    }

    static Stream<Arguments> confirmations() {
        return Stream.of("http", "rabbitmq").flatMap(transport -> Stream.of(
                Arguments.of(transport, "SUCCEEDED", false),
                Arguments.of(transport, "FAILED", false),
                Arguments.of(transport, "FAILED", true)));
    }

    @ParameterizedTest(name = "{0} confirmation after {1}, retry={2}")
    @MethodSource("confirmations")
    void callbackHoldingRunLockCannotBeOverwrittenByConfirmation(String transport, String state, boolean retryable) throws Exception {
        IRunService service = service(transport);
        var run = service.insert(request());
        var attempt = run.attempts().getFirst();
        var result = new Result(attempt.id(), run.id(), attempt.leaseVersion(), state, state, "completed", retryable);
        var callbackLocked = new CountDownLatch(1);
        var confirmationRead = new CountDownLatch(1);
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);

        try (var callbackPool = Executors.newSingleThreadExecutor(); var confirmationPool = Executors.newSingleThreadExecutor()) {
            var confirmationThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            doAnswer(call -> {
                var row = call.callRealMethod();
                if (Thread.currentThread() == confirmationThread.get() && callbackLocked.getCount() == 0)
                    confirmationRead.countDown();
                return row;
            }).when(mapper).attempt(attempt.id());

            Callable<Void> callback = () -> {
                transaction.executeWithoutResult(status -> {
                    mapper.lock(run.id()).orElseThrow();
                    callbackLocked.countDown();
                    await(confirmationRead);
                    service.updateResult(result);
                });
                return null;
            };
            Future<Void> callbackFuture;
            Future<?> confirmationFuture;
            if (transport.equals("http")) {
                var callbackStarted = new CompletableFuture<Future<Void>>();
                doAnswer(call -> {
                    callbackStarted.complete(callbackPool.submit(callback));
                    await(callbackLocked);
                    return null;
                }).when(worker).dispatch(any());
                confirmationFuture = confirmationPool.submit(() -> {
                    confirmationThread.set(Thread.currentThread());
                    service.dispatchPending();
                });
                callbackFuture = callbackStarted.get(10, TimeUnit.SECONDS);
            } else {
                service.dispatchPending();
                // 只模拟 Broker 已确认的数据库边界；消息准备与后续确认仍执行真实 Service/Mapper。
                jdbc.update("UPDATE dispatch_outbox SET state='PUBLISHED' WHERE attempt_id=?", attempt.id().toString());
                makeDue();
                callbackFuture = callbackPool.submit(callback);
                await(callbackLocked);
                confirmationFuture = confirmationPool.submit(() -> {
                    confirmationThread.set(Thread.currentThread());
                    service.dispatchPending();
                });
            }
            try {
                callbackFuture.get(10, TimeUnit.SECONDS);
                confirmationFuture.get(10, TimeUnit.SECONDS);
            } finally {
                confirmationRead.countDown();
            }
        }

        assertCompleted(service.selectById(run.id()), state, retryable);
        service.updateResult(result);
        service.updateResult(result);
        assertCompleted(service.selectById(run.id()), state, retryable);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentIdenticalResultsRemainIdempotentAndCreateAtMostOneRetry(boolean retryable) throws Exception {
        var run = runs.insert(request());
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        String state = retryable ? "FAILED" : "SUCCEEDED";
        var result = new Result(attempt.id(), run.id(), attempt.leaseVersion(), state, state, "completed", retryable);
        var bothRead = new CountDownLatch(2);
        var observed = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
        doAnswer(call -> {
            var row = call.callRealMethod();
            if (observed.add(Thread.currentThread())) {
                bothRead.countDown();
                await(bothRead);
            }
            return row;
        }).when(mapper).attempt(attempt.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runs.updateResult(result));
            var second = pool.submit(() -> runs.updateResult(result));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        // 主线程不参与上面的读屏障。
        doCallRealMethod().when(mapper).attempt(attempt.id());
        assertCompleted(runs.selectById(run.id()), state, retryable);
    }

    @Test void zeroRowAcceptanceDoesNotAdvanceRun() {
        doReturn(false).when(mapper).accepted(any(), any());
        var run = runs.insert(request());
        runs.dispatchPending();
        var after = runs.selectById(run.id());
        assertThat(after.state()).isEqualTo("READY");
        assertThat(after.attempts().getFirst().state()).isEqualTo("DISPATCHING");
        verify(mapper, never()).runState(any(), anyString(), anyInt(), any(), any(), any());
    }

    @Test void expiredConfirmationCannotMarkRunRunning() {
        doAnswer(call -> {
            Dispatch command = call.getArgument(0);
            jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?",
                    Timestamp.from(Instant.now().minusSeconds(1)), command.attemptId().toString());
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(request());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("READY");
        runs.dispatchPending();
        var after = runs.selectById(run.id());
        assertThat(after.state()).isEqualTo("FAILED");
        assertThat(after.attempts().getFirst().state()).isEqualTo("TIMED_OUT");
    }

    @Test void failedConfirmationRunUpdateRollsBackWithoutMisclassifyingWorkerRejection() {
        var run = runs.insert(request());
        doReturn(false).when(mapper).runState(any(), eq("RUNNING"), anyInt(), any(), any(), any());
        assertThatThrownBy(runs::dispatchPending).isInstanceOf(RunException.class);
        var waiting = runs.selectById(run.id());
        assertThat(waiting.state()).isEqualTo("READY");
        assertThat(waiting.resultCode()).isNull();
        assertThat(waiting.attempts().getFirst().state()).isEqualTo("DISPATCHING");
        doCallRealMethod().when(mapper).runState(any(), anyString(), anyInt(), any(), any(), any());
        makeDue();
        runs.dispatchPending();
        var restored = runs.selectById(run.id());
        assertThat(restored.state()).isEqualTo("RUNNING");
        assertThat(restored.attempts()).hasSize(1);
        assertThat(restored.attempts().getFirst().expiresAt()).isEqualTo(waiting.attempts().getFirst().expiresAt());
    }

    @Test void wrongLeaseAndConflictingResultsAreRejectedButIdenticalLateResultIsConfirmed() {
        var run = runs.insert(request());
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        var result = new Result(attempt.id(), run.id(), attempt.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "completed", false);
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion() + 1,
                "SUCCEEDED", "SUCCEEDED", "completed", false))).isInstanceOfSatisfying(RunException.class,
                failure -> assertThat(failure.status()).isEqualTo(409));
        runs.updateResult(result);
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(),
                "FAILED", "HANDLER_FAILED", "conflicting", true))).isInstanceOf(RunException.class);
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), attempt.id().toString());
        runs.updateResult(result);
        assertCompleted(runs.selectById(run.id()), "SUCCEEDED", false);
    }

    @Test void sqlGuardsRejectOldLeaseOldStateAndTerminalRunEvenWithStaleInputs() {
        var run = runs.insert(request());
        var id = run.attempts().getFirst().id();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var runRow = mapper.lock(run.id()).orElseThrow();
            mapper.dispatching(id, Instant.now().plusSeconds(30), Instant.now(), Instant.now());
            var dispatching = mapper.lockAttempt(id).orElseThrow();
            var oldLease = new Attempt(id, run.id(), dispatching.attemptNumber(), dispatching.leaseVersion() + 1,
                    dispatching.state(), dispatching.dispatchCount(), dispatching.nextDispatchAt(), dispatching.expiresAt(),
                    dispatching.startedAt(), null, null, null);
            assertThat(mapper.accepted(oldLease, Instant.now())).isFalse();
            assertThat(mapper.finish(oldLease, "SUCCEEDED", "SUCCEEDED", "completed", Instant.now())).isFalse();
            assertThat(mapper.accepted(dispatching, Instant.now())).isTrue();
            assertThat(mapper.finish(dispatching, "SUCCEEDED", "SUCCEEDED", "completed", Instant.now())).isFalse();
            var running = mapper.lockAttempt(id).orElseThrow();
            assertThat(mapper.finish(running, "SUCCEEDED", "SUCCEEDED", "completed", Instant.now())).isTrue();
            assertThat(mapper.accepted(dispatching, Instant.now())).isFalse();
            assertThat(mapper.runState(runRow, "SUCCEEDED", 1, "SUCCEEDED", "completed", Instant.now())).isTrue();
            assertThat(mapper.runState(runRow, "RUNNING", 1, null, null, Instant.now())).isFalse();
            var terminal = mapper.lock(run.id()).orElseThrow();
            assertThat(mapper.runState(terminal, "RUNNING", 1, null, null, Instant.now())).isFalse();
        });
        assertCompleted(runs.selectById(run.id()), "SUCCEEDED", false);
    }

    @Test void failedRunTransitionRollsBackAttemptRetryAndOutboxTogether() {
        var service = service("rabbitmq");
        var run = service.insert(request());
        service.dispatchPending();
        var attempt = service.selectById(run.id()).attempts().getFirst();
        doReturn(false).when(mapper).runState(any(), eq("RETRY_WAIT"), anyInt(), any(), any(), any());
        assertThatThrownBy(() -> service.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(),
                "FAILED", "HANDLER_FAILED", "completed", true))).isInstanceOfSatisfying(RunException.class,
                failure -> assertThat(failure.code()).isEqualTo("STALE_ATTEMPT"));
        var after = service.selectById(run.id());
        assertThat(after.state()).isEqualTo("READY");
        assertThat(after.attempts()).hasSize(1);
        assertThat(after.attempts().getFirst().state()).isEqualTo("DISPATCHING");
        assertThat(outbox.find(attempt.id(), false).orElseThrow().state()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_outbox", Long.class)).isEqualTo(1);
    }

    @Test void zeroRowFinishCannotCompleteRunOrCreateRetry() {
        var run = runs.insert(request());
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        doReturn(false).when(mapper).finish(any(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(),
                "FAILED", "HANDLER_FAILED", "completed", true))).isInstanceOf(RunException.class);
        var after = runs.selectById(run.id());
        assertThat(after.state()).isEqualTo("RUNNING");
        assertThat(after.attempts()).hasSize(1);
        assertThat(after.attempts().getFirst().state()).isEqualTo("RUNNING");
    }

    private IRunService service(String transport) {
        if (transport.equals("http")) return runs;
        var p = dispatchProperties;
        var mq = new DispatchProperties("rabbitmq", p.exchange(), p.queue(), p.routingKey(), p.deadLetterExchange(),
                p.deadLetterQueue(), p.deadLetterRoutingKey(), p.confirmTimeout(), p.claimLease(), p.retryDelay(),
                p.maxRetryDelay(), p.maxPublishAttempts(), false, p.nodeRecheckDelay());
        return new RunServiceImpl(mapper, worker, runProperties, json, manager, mq, outbox, nodes);
    }

    private void assertCompleted(RunView run, String state, boolean retryable) {
        assertThat(run.state()).isEqualTo(retryable ? "RETRY_WAIT" : state);
        assertThat(run.resultCode()).isEqualTo(state);
        assertThat(run.message()).isEqualTo("completed");
        assertThat(run.attempts()).hasSize(retryable ? 2 : 1);
        assertThat(run.attempts().getFirst().state()).isEqualTo(state);
        assertThat(run.attempts().getFirst().message()).isEqualTo("completed");
        if (retryable) assertThat(run.attempts().getLast().state()).isEqualTo("READY");
    }

    private Create request() {
        return new Create(UUID.randomUUID(), new Snapshot(7, 1, "state concurrency", "demoSuccess", json.readTree("{}"),
                30, 3, 5, "MANUAL", null, "UTC", "SKIP", false, null));
    }

    private void makeDue() {
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).as("concurrent transaction reached expected boundary").isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
