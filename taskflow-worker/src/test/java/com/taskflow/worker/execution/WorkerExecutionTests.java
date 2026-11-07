package com.taskflow.worker.execution;

import java.time.*;
import java.sql.Timestamp;
import java.util.UUID;
import java.net.URI;
import java.net.http.*;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.client.SchedulerReportClient.Outcome;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.config.ResultPersistenceProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionCompletion;
import com.taskflow.worker.execution.service.*;
import com.taskflow.worker.execution.service.impl.ExecutionServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@org.springframework.test.context.ContextConfiguration(initializers = com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class WorkerExecutionTests {
    @Autowired IExecutionService executions;
    @MockitoSpyBean ExecutionMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ExecutionProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IExecutionResultService results;
    @Autowired IExecutionLeaseService leases;
    @Autowired ResultPersistenceProperties resultProperties;
    @Autowired MeterRegistry metrics;
    @Value("${local.server.port}") int port;
    @MockitoBean SchedulerReportClient reports;
    @MockitoBean SchedulerLeaseClient leaseClient;
    @MockitoSpyBean IDemoHandlerService handlers;
    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-worker-");
        }
        jdbc.update("DELETE FROM worker_attempt_event");
        jdbc.update("DELETE FROM worker_execution");
        when(leaseClient.updateLease(any())).thenAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            var now = Instant.now();
            var command = json.readValue(mapper.find(request.attemptId()).orElseThrow().dispatchJson(), Dispatch.class);
            return new LeaseAcknowledgement(request.attemptId(), request.runId(), request.leaseVersion(), request.workerId(),
                    request.sessionId(), now, command.expiresAt(), command.expiresAt().minusSeconds(10), command.expiresAt());
        });
    }
    @AfterEach void savingHasStopped() {
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(metrics.get("taskflow.worker.result.persistence.active").gauge().value()).isZero());
    }
    @Test void authenticatedHttpPersistsBeforeAcknowledgingAndDeduplicates() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        assertThat(post(command, null).statusCode()).isEqualTo(401);
        assertThat(post(command, "test-dispatch-token").statusCode()).isEqualTo(202);
        assertThat(post(command, "test-dispatch-token").statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_execution", Long.class)).isEqualTo(1);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
        var changed = new Dispatch(command.attemptId(), UUID.randomUUID(), command.attemptNumber(), command.leaseVersion(), command.expiresAt(), command.snapshot());
        assertThat(post(changed, "test-dispatch-token").statusCode()).isEqualTo(409);
    }
    @Test void successIsAsynchronousAndDuplicateDispatchDoesNotExecuteTwice() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        executions.insert(command); executions.executePending(); finished(command);
        executions.insert(command); executions.executePending();
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        verify(handlers, times(1)).execute(any(), any());
    }
    @Test void transientResultSaveFailureRecoversWithoutRepeatingHandler() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            var answer = call.callRealMethod();
            if (saves.incrementAndGet() == 1)
                throw new org.springframework.dao.DataAccessResourceFailureException("temporary failure after local writes");
            return answer;
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        executions.insert(command); executions.executePending(); finished(command);
        executions.insert(command); executions.executePending();
        assertThat(saves.get()).isEqualTo(2);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.state()).isEqualTo("SUCCEEDED");
        assertThat(row.message()).isEqualTo("执行成功");
        verify(handlers, times(1)).execute(any(), any());
        when(reports.report(any())).thenReturn(Outcome.ACKNOWLEDGED);
        executions.reportPending();
        assertThat(mapper.find(command.attemptId()).orElseThrow().reported()).isTrue();
        verify(reports).report(argThat(result -> result.attemptId().equals(command.attemptId())
                && result.status().equals("SUCCEEDED") && result.message().equals("执行成功")));
        assertThat(eventTypes(command)).containsExactly("HANDLER_STARTED", "HANDLER_FINISHED", "REPORT_CLAIMED", "REPORT_ACKNOWLEDGED");
    }
    @Test void rejectedExecutorSettlesClaimWithoutCallingHandler() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (saves.incrementAndGet() == 1)
                throw new org.springframework.dao.DataAccessResourceFailureException("temporary save failure");
            return call.callRealMethod();
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        var stopped = new ExecutionServiceImpl(mapper, new com.taskflow.worker.execution.service.impl.HandlerRegistryServiceImpl(java.util.List.of(handlers)), reports, properties, json, transactions, results, leases);
        try {
            stopped.recover(); stopped.insert(command); stopped.shutdown();
            assertThatCode(stopped::executePending).doesNotThrowAnyException();
            var row = mapper.find(command.attemptId()).orElseThrow();
            assertThat(row.state()).isEqualTo("FAILED");
            assertThat(row.resultCode()).isEqualTo("EXECUTOR_REJECTED");
            assertThat(row.retryable()).isFalse();
            assertThat(mapper.pendingCount()).isZero();
            assertThat(saves.get()).isEqualTo(2);
            verify(handlers, never()).execute(any(), any());
            assertThat(eventTypes(command)).containsExactly("EXECUTION_FINISHED");
        } finally { stopped.shutdown(); }
    }
    @Test void handlerIsNotInvokedWhenItsEntryEventCannotBeStored() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("event store unavailable"))
                .when(mapper).startHandler(eq(command.attemptId()), any());
        executions.insert(command);
        executions.executePending();
        finished(command);
        verify(handlers, never()).execute(any(), any());
        assertThat(mapper.find(command.attemptId()).orElseThrow().resultCode()).isEqualTo("HANDLER_START_UNRECORDED");
        assertThat(eventTypes(command)).containsExactly("EXECUTION_FINISHED");
    }
    @Test void handlerFailureRetainsOriginalResultAndRetryPolicyAfterSaveRecovery() throws Exception {
        var command = command("demoFail", 1, 30, "{}");
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (saves.incrementAndGet() == 1)
                throw new org.springframework.dao.DataAccessResourceFailureException("temporary failure");
            return call.callRealMethod();
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        executions.insert(command); executions.executePending(); finished(command);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.resultCode()).isEqualTo("HANDLER_FAILED");
        assertThat(row.retryable()).isTrue();
        when(reports.report(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Result result = call.getArgument(0);
            assertThat(result.leaseVersion()).isEqualTo(command.leaseVersion());
            assertThat(result.runId()).isEqualTo(command.runId());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.resultCode()).isEqualTo("HANDLER_FAILED");
            assertThat(result.retryable()).isTrue();
            return Outcome.ACKNOWLEDGED;
        });
        executions.reportPending();
        assertThat(mapper.find(command.attemptId()).orElseThrow().reported()).isTrue();
        verify(handlers, times(1)).execute(any(), any());
    }
    @Test void committedResultWithLostConfirmationIsRecognizedWithoutResettingReportState() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        var originalFinish = new java.util.concurrent.atomic.AtomicReference<Instant>();
        doAnswer(call -> {
            var answer = call.callRealMethod();
            if (saves.incrementAndGet() == 1) {
                originalFinish.set(mapper.find(command.attemptId()).orElseThrow().finishedAt());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        var independent = new TransactionTemplate(transactions);
                        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        independent.executeWithoutResult(status -> {
                            mapper.reportClaim(command.attemptId(), 0, Instant.now().plusSeconds(60));
                            mapper.recordReportOutcome(command.attemptId(), 1, "REPORT_ACKNOWLEDGED", true, Instant.now());
                        });
                        throw new org.springframework.dao.DataAccessResourceFailureException("commit confirmation lost");
                    }
                });
            }
            return answer;
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        var recoveredBefore = outcome("recovered");
        executions.insert(command); executions.executePending();
        await().atMost(Duration.ofSeconds(4)).until(() -> outcome("recovered") == recoveredBefore + 1);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.state()).isEqualTo("SUCCEEDED");
        assertThat(row.finishedAt()).isEqualTo(originalFinish.get());
        assertThat(row.reportCount()).isEqualTo(1);
        assertThat(row.reported()).isTrue();
        executions.insert(command); executions.executePending(); executions.reportPending();
        assertThat(saves.get()).isEqualTo(2);
        verify(handlers, times(1)).execute(any(), any());
        verifyNoInteractions(reports);
        assertThat(eventTypes(command)).containsExactly("HANDLER_STARTED", "HANDLER_FINISHED", "REPORT_CLAIMED", "REPORT_ACKNOWLEDGED");
    }
    @Test void exhaustedSavingIsFiniteTraceableAndDoesNotRepeatUnknownBusiness(CapturedOutput output) throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var exhaustedBefore = outcome("exhausted");
        doAnswer(call -> {
            attempts.incrementAndGet();
            throw new org.springframework.dao.DataAccessResourceFailureException("sensitive-db-error-do-not-log");
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        executions.insert(command); executions.executePending();
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(outcome("exhausted")).isEqualTo(exhaustedBefore + 1);
            assertThat(output.getOut()).contains("code=RESULT_SAVE_EXHAUSTED attemptId=" + command.attemptId());
        });
        assertThat(attempts.get()).isEqualTo(resultProperties.maxAttempts());
        assertThat(output.getOut()).contains("runId=" + command.runId(), "intendedState=SUCCEEDED", "resultCode=SUCCEEDED", "attempts=3")
                .doesNotContain("sensitive-db-error-do-not-log");
        for (int i = 0; i < 3; i++) {
            executions.insert(command); executions.executePending(); executions.reportPending();
        }
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("RUNNING");
        verify(handlers, times(1)).execute(any(), any());
        verifyNoInteractions(reports);
        // 模拟丢失内存结果后的启动恢复：只记录副作用未知，不重新执行。
        executions.recover();
        var recovered = mapper.find(command.attemptId()).orElseThrow();
        assertThat(recovered.resultCode()).isEqualTo("WORKER_RESTARTED");
        assertThat(recovered.retryable()).isFalse();
        executions.executePending();
        verify(handlers, times(1)).execute(any(), any());
    }
    @Test void conflictingOrMissingTerminalRecordIsNotReportedAsSaved(CapturedOutput output) {
        var command = command("demoSuccess", 1, 30, "{}");
        executions.insert(command); mapper.claim(command.attemptId(), leases.owner());
        mapper.finish(command.attemptId(), "FAILED", "WORKER_RESTARTED", "unknown", false, false, Instant.now());
        var conflictsBefore = outcome("conflict");
        var completion = new ExecutionCompletion(command.attemptId(), command.runId(), "SUCCEEDED", "SUCCEEDED", "done", false, true, Instant.now());
        assertThat(results.save(completion)).isFalse();
        assertThat(mapper.find(command.attemptId()).orElseThrow().resultCode()).isEqualTo("WORKER_RESTARTED");
        var missing = new ExecutionCompletion(UUID.randomUUID(), command.runId(), "SUCCEEDED", "SUCCEEDED", "done", false, true, Instant.now());
        assertThat(results.save(missing)).isFalse();
        assertThat(outcome("conflict")).isEqualTo(conflictsBefore + 2);
        assertThat(output.getOut()).contains("code=RESULT_SAVE_CONFLICT");
        verify(mapper, times(2)).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        verify(mapper, times(1)).finish(eq(missing.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
    }
    @Test void resultSavingKeepsOriginalSlotsAndBoundsAdmissionDuringDatabaseFailure() throws Exception {
        var first = command("demoSuccess", 1, 30, "{}");
        var second = command("demoSuccess", 1, 30, "{}");
        var third = command("demoSuccess", 1, 30, "{}");
        var fourth = command("demoSuccess", 1, 30, "{}");
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var attempts = new java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicInteger>();
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            if ((id.equals(first.attemptId()) || id.equals(second.attemptId()))
                    && attempts.computeIfAbsent(id, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(4, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                throw new org.springframework.dao.DataAccessResourceFailureException("temporary failure");
            }
            return call.callRealMethod();
        }).when(mapper).finish(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        try {
            executions.insert(first); executions.insert(second); executions.executePending();
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            executions.insert(third); executions.insert(fourth); executions.executePending();
            executions.insert(first); executions.executePending();
            assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
            assertThat(mapper.find(fourth.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
            assertThat(metrics.get("taskflow.worker.result.persistence.active").gauge().value()).isEqualTo(2);
            assertThatThrownBy(() -> executions.insert(command("demoSuccess", 1, 30, "{}")))
                    .isInstanceOfSatisfying(ExecutionException.class, error -> assertThat(error.status()).isEqualTo(429));
            verify(handlers, times(2)).execute(any(), any());
        } finally { release.countDown(); }
        finished(first); finished(second);
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            executions.executePending();
            assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
            assertThat(mapper.find(fourth.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        });
        verify(handlers, times(4)).execute(any(), any());
    }
    @Test void claimDatabaseFailureReleasesSlotAndCanResumeOriginalAcceptedWork() throws Exception {
        var command = command("demoSuccess", 1, 30, "{}");
        executions.insert(command);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("claim failed")).when(mapper).claim(command.attemptId(), leases.owner());
        assertThatThrownBy(executions::executePending).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
        verify(handlers, never()).execute(any(), any());
        doCallRealMethod().when(mapper).claim(command.attemptId(), leases.owner());
        executions.executePending(); finished(command);
        verify(handlers, times(1)).execute(any(), any());
    }
    private double outcome(String outcome) {
        return metrics.get("taskflow.worker.result.persistence").tag("outcome", outcome).counter().count();
    }
    @Test void failedReportRetriesPersistentlyAndStopsAfterSuccess() {
        var command = command("demoSuccess", 1, 30, "{}");
        executions.insert(command); executions.executePending(); finished(command);
        when(reports.report(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return Outcome.UNCONFIRMED;
        });
        executions.reportPending();
        assertThat(mapper.find(command.attemptId()).orElseThrow().reported()).isFalse();
        when(reports.report(any())).thenReturn(Outcome.ACKNOWLEDGED); reportDue(); executions.reportPending();
        assertThat(mapper.find(command.attemptId()).orElseThrow().reported()).isTrue();
        executions.reportPending(); verify(reports, times(2)).report(any());
        assertThat(eventTypes(command)).containsExactly("HANDLER_STARTED", "HANDLER_FINISHED",
                "REPORT_CLAIMED", "REPORT_UNCONFIRMED", "REPORT_CLAIMED", "REPORT_ACKNOWLEDGED");
        assertThat(jdbc.queryForList("SELECT report_number FROM worker_attempt_event WHERE attempt_id=? AND report_number IS NOT NULL ORDER BY id",
                Integer.class, command.attemptId().toString())).containsExactly(1, 1, 2, 2);
    }
    @Test void failOnceUsesAttemptNumberAndProducesRetryableFailure() {
        var first = command("demoFailOnce", 1, 30, "{}");
        var second = command("demoFailOnce", 2, 30, "{}");
        executions.insert(first); executions.insert(second); executions.executePending();
        finished(first); finished(second);
        assertThat(mapper.find(first.attemptId()).orElseThrow().resultCode()).isEqualTo("HANDLER_FAILED");
        assertThat(mapper.find(first.attemptId()).orElseThrow().retryable()).isTrue();
        assertThat(mapper.find(second.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void timeoutInterruptsCooperativeHandlerAndDoesNotRequestUnsafeRetry() {
        var command = command("demoSlow", 1, 1, "{\"durationMillis\":1600}");
        executions.insert(command); executions.executePending(); finished(command);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.resultCode()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(row.retryable()).isFalse();
    }
    @Test void restartRecoveryPreservesAcceptedAndFailsUnknownRunningWork() throws Exception {
        var unknown = command("demoSuccess", 1, 30, "{}");
        var accepted = command("demoSuccess", 1, 30, "{}");
        executions.insert(unknown); mapper.claim(unknown.attemptId(), leases.owner()); executions.insert(accepted);
        executions.recover();
        assertThat(mapper.find(unknown.attemptId()).orElseThrow().resultCode()).isEqualTo("WORKER_RESTARTED");
        assertThat(mapper.find(accepted.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
        executions.executePending(); finished(accepted);
        verify(handlers, times(1)).execute(any(), any());
    }
    @Test void restartReportingKeepsThePersistedExecutionOwnerInsteadOfUsingTheNewProcessSession() {
        var command = command("demoSuccess", 1, 30, "{}");
        var oldOwner = new com.taskflow.worker.execution.model.ExecutionOwner("worker-default", UUID.randomUUID());
        executions.insert(command); mapper.claim(command.attemptId(), oldOwner);
        executions.recover();
        when(reports.report(any())).thenReturn(Outcome.STALE);
        reportDue(); executions.reportPending();
        verify(reports).report(argThat(result -> result.workerId().equals(oldOwner.workerId())
                && result.sessionId().equals(oldOwner.sessionId()) && result.resultCode().equals("WORKER_RESTARTED")));
        assertThat(mapper.find(command.attemptId()).orElseThrow().reportSessionId()).isEqualTo(oldOwner.sessionId());
        assertThat(leases.owner().sessionId()).isNotEqualTo(oldOwner.sessionId());
        assertThat(mapper.find(command.attemptId()).orElseThrow().reported()).isTrue();
        assertThat(eventTypes(command)).containsExactly("EXECUTION_RECOVERED", "REPORT_CLAIMED", "REPORT_STALE");
        assertThat(jdbc.queryForList("SELECT DISTINCT worker_id FROM worker_attempt_event WHERE attempt_id=?",
                String.class, command.attemptId().toString())).containsExactly(oldOwner.workerId());
    }
    @Test void unsupportedExpiredOrOverCapacityRequestsAreRejected() {
        assertThatThrownBy(() -> executions.insert(command("notAllowed", 1, 30, "{}"))).isInstanceOf(ExecutionException.class);
        var normal = command("demoSuccess", 1, 30, "{}");
        var expired = new Dispatch(normal.attemptId(), normal.runId(), 1, 1, Instant.now().minusSeconds(1), normal.snapshot());
        assertThatThrownBy(() -> executions.insert(expired)).isInstanceOf(ExecutionException.class);
        for (int i = 0; i < 4; i++) executions.insert(command("demoSuccess", 1, 30, "{}"));
        assertThatThrownBy(() -> executions.insert(command("demoSuccess", 1, 30, "{}")))
                .isInstanceOfSatisfying(ExecutionException.class, error -> assertThat(error.status()).isEqualTo(429));
    }
    @Test void resultReportingHasAFiniteAttemptBudget() {
        var command = command("demoSuccess", 1, 30, "{}");
        executions.insert(command); executions.executePending(); finished(command);
        when(reports.report(any())).thenReturn(Outcome.UNCONFIRMED);
        for (int i = 0; i < 5; i++) { reportDue(); executions.reportPending(); }
        assertThat(mapper.find(command.attemptId()).orElseThrow().reportCount()).isEqualTo(3);
        verify(reports, times(3)).report(any());
        assertThat(eventTypes(command)).endsWith("REPORT_CLAIMED", "REPORT_EXHAUSTED");
    }

    private java.util.List<String> eventTypes(Dispatch command) {
        return jdbc.queryForList("SELECT event_type FROM worker_attempt_event WHERE run_id=? AND attempt_id=? ORDER BY id",
                String.class, command.runId().toString(), command.attemptId().toString());
    }
    @Test void slowReportingDefersRemainingResultsWithoutClaimingAfterBudget() {
        var commands = java.util.List.of(command("demoSuccess", 1, 30, "{}"),
                command("demoFail", 1, 30, "{}"), command("demoSuccess", 1, 30, "{}"));
        for (var command : commands) {
            executions.insert(command); mapper.claim(command.attemptId(), leases.owner());
            var state = command.snapshot().handlerKey().equals("demoFail") ? "FAILED" : "SUCCEEDED";
            mapper.finish(command.attemptId(), state, state, "", false, false, Instant.now().minusSeconds(1));
        }
        var rows = mapper.reports(Instant.now(), properties.maxReportAttempts());
        assertThat(rows).hasSize(3).allSatisfy(row -> assertThat(row.reported()).isFalse());
        var sent = new java.util.ArrayList<Result>();
        when(reports.report(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Result result = call.getArgument(0);
            assertThat(mapper.find(result.attemptId()).orElseThrow().reportCount()).isEqualTo(1);
            sent.add(result); Thread.sleep(200); return Outcome.ACKNOWLEDGED;
        });
        var limited = new ExecutionProperties(properties.schedulerUrl(), properties.dispatchToken(), properties.reportToken(),
                properties.connectTimeout(), properties.requestTimeout(), properties.scanInterval(), properties.parallelism(),
                properties.maxPending(), properties.maxReportAttempts(), properties.reportRetryDelay(), false, Duration.ofMillis(1));
        var reporting = new ExecutionServiceImpl(mapper, new com.taskflow.worker.execution.service.impl.HandlerRegistryServiceImpl(java.util.List.of(handlers)), reports, limited, json, transactions, results, leases);
        try {
            reporting.recover(); reporting.reportPending();
            verify(reports, atMostOnce()).report(any());
            assertThat(sent).hasSizeLessThanOrEqualTo(1);
            for (var before : rows) {
                var after = mapper.find(before.attemptId()).orElseThrow();
                boolean wasSent = sent.stream().anyMatch(result -> result.attemptId().equals(before.attemptId()));
                assertThat(after.reported()).isEqualTo(wasSent);
                assertThat(after.reportCount()).isEqualTo(wasSent ? 1 : 0);
                if (!wasSent) assertThat(after.nextReportAt()).isEqualTo(before.nextReportAt());
            }
        } finally { reporting.shutdown(); }
    }
    private void finished(Dispatch command) {
        await().atMost(Duration.ofSeconds(4)).until(() -> java.util.Set.of("SUCCEEDED", "FAILED").contains(mapper.find(command.attemptId()).orElseThrow().state()));
    }
    private void reportDue() { jdbc.update("UPDATE worker_execution SET next_report_at=?", Timestamp.from(Instant.now().minusSeconds(1))); }
    private Dispatch command(String handler, int number, int timeout, String payload) {
        return new Dispatch(UUID.randomUUID(), UUID.randomUUID(), number, number, Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                new Snapshot(7, 1, "Worker test", handler, json.readTree(payload), timeout, 3, 1, "MANUAL", null, "Asia/Shanghai", "SKIP", false, null));
    }
    private HttpResponse<String> post(Dispatch command, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/executions/insert"))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("X-Taskflow-Token", token);
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
