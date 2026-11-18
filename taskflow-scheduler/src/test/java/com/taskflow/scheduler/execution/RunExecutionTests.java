package com.taskflow.scheduler.execution;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.remote.client.WorkerAttemptEventsRemote;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class RunExecutionTests {
    @Autowired IRunService runs;
    @Autowired com.taskflow.scheduler.history.service.IHistoryArchiveService history;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean WorkerClient worker;
    @MockitoBean WorkerAttemptEventsRemote workerEvents;
    @MockitoSpyBean RunDataMapper mapper;
    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-scheduler-");
        }
        jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
    }
    @Test void httpAuthenticationValidationAndIdempotentCreate() throws Exception {
        var request = create(UUID.randomUUID(), "success", 1);
        assertThat(http("POST", "/internal/v1/runs/insert", request, null).statusCode()).isEqualTo(401);
        assertThat(http("POST", "/internal/v1/runs/insert", request, "wrong").statusCode()).isEqualTo(401);
        assertThat(http("POST", "/internal/v1/attempts/updateResult", null, "test-control-token").statusCode()).isEqualTo(401);
        var first = http("POST", "/internal/v1/runs/insert", request, "test-control-token");
        var second = http("POST", "/internal/v1/runs/insert", request, "test-control-token");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        assertThat(json.readTree(first.body()).get("id")).isEqualTo(json.readTree(second.body()).get("id"));
        assertThat(http("POST", "/internal/v1/runs/insert", create(request.requestKey(), "changed", 1), "test-control-token").statusCode()).isEqualTo(409);
        assertThat(http("GET", "/internal/v1/runs/select?jobId=7&page=0", null, "test-control-token").statusCode()).isEqualTo(400);
        assertThat(http("GET", "/internal/v1/runs/selectByRequestKey?requestKey=" + UUID.randomUUID(), null, "test-control-token").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isEqualTo(1);
    }
    @Test void concurrentSameRequestCreatesOnlyOneRunAndOneAttempt() throws Exception {
        var input = create(UUID.randomUUID(), "concurrent", 1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> runs.insert(input));
            var b = executor.submit(() -> runs.insert(input));
            assertThat(a.get(5, TimeUnit.SECONDS).id()).isEqualTo(b.get(5, TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt", Long.class)).isEqualTo(1);
    }
    @Test void archiveKeepsSnapshotsTimelineAndManualAndCronIdentity() {
        doAnswer(call -> {
            Dispatch command = call.getArgument(0);
            runs.updateResult(new Result(command.attemptId(), command.runId(), command.leaseVersion(),
                    "SUCCEEDED", "SUCCEEDED", "original result", false, "worker-default",
                    UUID.fromString("00000000-0000-0000-0000-000000000001")));
            return null;
        }).when(worker).dispatch(any());
        var manual = create(UUID.randomUUID(), "archive", 1);
        var manualId = runs.insert(manual).id();
        var old = Instant.now().minus(Duration.ofDays(100)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var cron = new Snapshot(8, 1, "archive cron", "demoSuccess", null, 30, 1, 5,
                "CRON", "0 * * * * *", "Asia/Shanghai", "SKIP", true, null);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var cronId = tx.execute(status -> runs.insertScheduled(cron, old, old));
        runs.dispatchPending();
        var manualBefore = runs.selectById(manualId);
        var cronBefore = runs.selectById(cronId);
        assertThat(manualBefore.state()).isEqualTo("SUCCEEDED");
        assertThat(cronBefore.state()).isEqualTo("SUCCEEDED");
        var attemptId = manualBefore.attempts().getFirst().id();
        var events = mapper.selectAttemptEvents(manualId, attemptId, 100);
        assertThat(events).isNotEmpty();
        var activeId = runs.insert(create(UUID.randomUUID(), "in flight", 1)).id();
        jdbc.update("UPDATE run_execution SET finished_at=? WHERE id IN (?, ?, ?)",
                Timestamp.from(old), manualId.toString(), cronId.toString(), activeId.toString());

        assertThat(history.runBatch(true)).containsEntry("runs", 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_snapshot_archive", Long.class)).isZero();
        assertThat(history.runBatch(false)).containsEntry("runs", 2);
        assertThat(history.runBatch(false)).containsEntry("runs", 0);
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM run_execution WHERE id=?", String.class, activeId.toString()))
                .isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM run_execution WHERE id=?", String.class, manualId.toString()))
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_attempt_event WHERE run_id=?", Long.class, manualId.toString()))
                .isZero();
        assertThat(mapper.selectAttemptEvents(manualId, attemptId, 100)).isEqualTo(events);
        assertThat(runs.selectById(manualId).snapshot()).isEqualTo(manualBefore.snapshot());
        assertThat(runs.selectByRequestKey(manual.requestKey()).attempts()).isEqualTo(manualBefore.attempts());
        var attempt = manualBefore.attempts().getFirst();
        runs.updateResult(new Result(attempt.id(), manualId, attempt.leaseVersion(),
                "SUCCEEDED", "SUCCEEDED", "original result", false, "worker-default",
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertThat(runs.insert(manual).id()).isEqualTo(manualId);
        assertThatThrownBy(() -> runs.insert(create(manual.requestKey(), "changed", 1)))
                .isInstanceOf(RunException.class);
        UUID replayedCron = tx.execute(status -> runs.insertScheduled(cron, old, old));
        assertThat(replayedCron).isEqualTo(cronId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isEqualTo(3);
    }
    @Test void creationRollsBackIfFirstAttemptCannotBeStored() {
        doThrow(new IllegalStateException("injected")).when(mapper).insertAttempt(any());
        assertThatThrownBy(() -> runs.insert(create(UUID.randomUUID(), "rollback", 1))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isZero();
    }
    @Test void concurrentRerunKeepsItsOriginAndActorAndReusesTheAcceptedRun() throws Exception {
        var source = failedBeforeHandler();
        assertThat(runs.selectById(source).rerunEligible()).isTrue();
        var snapshot = create(UUID.randomUUID(), "current configuration", 1).snapshot();
        var input = new Rerun(UUID.randomUUID(), source, "operator-1", snapshot);
        assertThat(http("POST", "/internal/v1/runs/insertRerun", input, null).statusCode()).isEqualTo(401);
        UUID rerunId;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> runs.insertRerun(input));
            var second = executor.submit(() -> runs.insertRerun(input));
            var accepted = first.get(5, TimeUnit.SECONDS);
            rerunId = accepted.id();
            assertThat(rerunId).isNotEqualTo(source).isEqualTo(second.get(5, TimeUnit.SECONDS).id());
            assertThat(accepted.sourceRunId()).isEqualTo(source);
            assertThat(accepted.rerunActor()).isEqualTo("operator-1");
            assertThat(accepted.attempts()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt", Long.class)).isEqualTo(2);
        assertThat(runs.selectById(source).state()).isEqualTo("FAILED");
        assertThat(runs.selectByRequestKey(input.requestKey()).sourceRunId()).isEqualTo(source);
        assertThat(http("POST", "/internal/v1/runs/insertRerun",
                new Rerun(input.requestKey(), source, "operator-2", snapshot), "test-control-token").statusCode()).isEqualTo(409);
        assertThat(http("POST", "/internal/v1/runs/insertRerun",
                new Rerun(input.requestKey(), UUID.randomUUID(), "operator-1", snapshot), "test-control-token").statusCode()).isEqualTo(409);
        assertThat(http("POST", "/internal/v1/runs/insert",
                new Create(input.requestKey(), snapshot), "test-control-token").statusCode()).isEqualTo(409);
    }
    @Test void rerunRejectsAnEarlierHandlerFailureEvenIfTheLastAttemptNeverStarted() throws Exception {
        doAnswer(call -> {
            Dispatch dispatch = call.getArgument(0);
            boolean first = dispatch.attemptNumber() == 1;
            reportFailure(dispatch, first ? "HANDLER_FAILED" : "EXECUTOR_REJECTED", first);
            return null;
        }).when(worker).dispatch(any());
        var source = runs.insert(create(UUID.randomUUID(), "mixed", 2));
        runs.dispatchPending();
        makeDue();
        runs.dispatchPending();
        var failed = runs.selectById(source.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.resultCode()).isEqualTo("EXECUTOR_REJECTED");
        assertThat(failed.rerunEligible()).isFalse();
        var input = new Rerun(UUID.randomUUID(), source.id(), "operator-1", source.snapshot());
        assertThat(http("POST", "/internal/v1/runs/insertRerun", input, "test-control-token").statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isEqualTo(1);
    }
    @Test void rerunAuditRollsBackWithItsFirstAttempt() {
        var source = failedBeforeHandler();
        doThrow(new IllegalStateException("injected")).when(mapper).insertAttempt(any());
        var input = new Rerun(UUID.randomUUID(), source, "operator-1", runs.selectById(source).snapshot());
        assertThatThrownBy(() -> runs.insertRerun(input)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution WHERE source_run_id IS NOT NULL", Long.class)).isZero();
        assertThat(mapper.byKey(input.requestKey())).isEmpty();
    }
    private UUID failedBeforeHandler() {
        doAnswer(call -> {
            reportFailure(call.getArgument(0), "EXECUTION_PREPARATION_FAILED", false);
            return null;
        }).when(worker).dispatch(any());
        var source = runs.insert(create(UUID.randomUUID(), "failed preparation", 1));
        runs.dispatchPending();
        return source.id();
    }
    private void reportFailure(Dispatch dispatch, String code, boolean retryable) {
        runs.updateResult(new Result(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(),
                "FAILED", code, "injected", retryable, "worker-default",
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }
    @Test void callbackBeforeDispatchResponseCannotRegressTerminalState() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Dispatch dispatch = call.getArgument(0);
            runs.updateResult(new Result(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "ok", false, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001")));
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "fast", 1));
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(runs.selectById(run.id()).attempts()).hasSize(1);
        var timing = runs.selectById(run.id()).timing();
        assertThat(timing.endToEndMillis()).isNotNegative();
        assertThat(timing.initialQueueMillis()).isNotNegative();
        assertThat(timing.scheduleDelayMillis()).isNull();
        var summary = runs.selectSummary(null, null);
        assertThat(summary.endToEndLatency().sampled()).isEqualTo(1);
        assertThat(summary.endToEndLatency().p95Millis()).isEqualTo(timing.endToEndMillis());
        assertThat(summary.latency().sampled()).isZero();
        assertThat(eventTypes(run.id(), run.attempts().getFirst().id())).containsExactly(
                "HTTP_DISPATCH_CLAIMED", "ATTEMPT_FINISHED", "RUN_FINISHED", "HTTP_DISPATCH_ACCEPTED");
    }
    @Test void transportRetryReusesAttemptAndExactlyTheSameLeaseDeadline() {
        var captured = new java.util.ArrayList<Dispatch>();
        doAnswer(call -> {
            Dispatch dispatch = call.getArgument(0); captured.add(dispatch);
            if (captured.size() == 1) throw new RunException(503, "OFFLINE", "injected");
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "retry", 1));
        runs.dispatchPending(); makeDue(); runs.dispatchPending();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).isEqualTo(captured.get(1));
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RUNNING");
    }
    @Test void validTerminalIsIdempotentAndWrongLeaseOrConflictingResultIsRejected() {
        var run = runs.insert(create(UUID.randomUUID(), "lease", 1));
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), 99, "SUCCEEDED", "SUCCEEDED", "ok", false, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                .isInstanceOf(RunException.class);
        var result = new Result(attempt.id(), run.id(), attempt.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "ok", false, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        runs.updateResult(result); runs.updateResult(result);
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(), "FAILED", "HANDLER_FAILED", "bad", true, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                .isInstanceOf(RunException.class);
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(eventTypes(run.id(), attempt.id())).containsExactly(
                "HTTP_DISPATCH_CLAIMED", "HTTP_DISPATCH_ACCEPTED", "ATTEMPT_FINISHED", "RUN_FINISHED");
    }
    @Test void businessRetryCreatesNewAttemptWithinTheSameRunAndHonorsDelay() {
        doAnswer(call -> {
            Dispatch dispatch = call.getArgument(0);
            boolean first = dispatch.attemptNumber() == 1;
            runs.updateResult(new Result(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(),
                    first ? "FAILED" : "SUCCEEDED", first ? "HANDLER_FAILED" : "SUCCEEDED", first ? "fail" : "ok", first, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001")));
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "business-retry", 2));
        runs.dispatchPending();
        var waiting = runs.selectById(run.id());
        assertThat(waiting.state()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.attempts()).hasSize(2);
        assertThat(Duration.between(waiting.attempts().getFirst().finishedAt(), waiting.attempts().get(1).nextDispatchAt()))
                .isEqualTo(Duration.ofSeconds(5));
        runs.dispatchPending();
        verify(worker, times(1)).dispatch(any());
        makeDue(); runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(runs.selectById(run.id()).attempts().get(1).id()).isNotEqualTo(waiting.attempts().getFirst().id());
        var firstAttempt = waiting.attempts().getFirst().id();
        var secondAttempt = waiting.attempts().get(1).id();
        assertThat(eventTypes(run.id(), firstAttempt)).contains("ATTEMPT_FINISHED").doesNotContain("RUN_FINISHED");
        assertThat(eventTypes(run.id(), secondAttempt)).contains("ATTEMPT_FINISHED", "RUN_FINISHED");
    }
    @Test void uncertainDispatchExhaustionNeverCreatesBusinessRetry() {
        doThrow(new RunException(503, "OFFLINE", "injected")).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "unconfirmed", 3));
        for (int i = 0; i < 6; i++) { makeDue(); runs.dispatchPending(); }
        var failed = runs.selectById(run.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.resultCode()).isEqualTo("DISPATCH_UNCONFIRMED");
        assertThat(failed.attempts()).hasSize(1);
        verify(worker, times(5)).dispatch(any());
    }
    @Test void exponentialRetryPersistsBoundedDelayAndStopsAtTheAttemptLimit() {
        doAnswer(call -> {
            reportFailure(call.getArgument(0), "HANDLER_FAILED", true);
            return null;
        }).when(worker).dispatch(any());
        var snapshot = new Snapshot(7, 2, "exponential", "demoSuccess", null, 30, 4, 5, "MANUAL",
                null, "Asia/Shanghai", "SKIP", true, null, "EXPONENTIAL", 12, null, null);
        var run = runs.insert(new Create(UUID.randomUUID(), snapshot));
        long[] ceilings = {5, 10, 12};
        for (int index = 0; index < ceilings.length; index++) {
            makeDue();
            runs.dispatchPending();
            var waiting = runs.selectById(run.id());
            assertThat(waiting.state()).isEqualTo("RETRY_WAIT");
            assertThat(waiting.attempts()).hasSize(index + 2);
            var delay = Duration.between(waiting.attempts().get(index).finishedAt(),
                    waiting.attempts().get(index + 1).nextDispatchAt());
            assertThat(delay.toMillis()).isBetween(ceilings[index] * 800, ceilings[index] * 1000);
        }
        makeDue();
        runs.dispatchPending();
        var failed = runs.selectById(run.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.attempts()).hasSize(4);
    }

    @Test void explicitFixedPolicyReplaysTheLegacyRunFingerprint() {
        var legacy = create(UUID.randomUUID(), "legacy", 3);
        var stored = runs.insert(legacy);
        var body = (tools.jackson.databind.node.ObjectNode) json.valueToTree(legacy.snapshot());
        assertThat(body.has("retryPolicy")).isFalse();
        assertThat(body.has("retryMaxIntervalSeconds")).isFalse();
        body.put("retryPolicy", "FIXED");
        var replay = new Create(legacy.requestKey(), json.treeToValue(body, Snapshot.class));
        assertThat(runs.insert(replay).id()).isEqualTo(stored.id());
    }
    @Test void leaseExpiryIsTerminalAndRejectsLateWorkerResult() {
        var run = runs.insert(create(UUID.randomUUID(), "expiry", 3));
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), attempt.id().toString());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT result_code FROM scheduler_attempt_event WHERE attempt_id=? AND event_type='ATTEMPT_FINISHED'",
                String.class, attempt.id().toString())).isEqualTo("LEASE_EXPIRED");
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "late", false, "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                .isInstanceOf(RunException.class);
    }
    @Test void timelineChecksAttemptOwnershipAndPreservesSchedulerEventsWhenWorkerUnavailable() throws Exception {
        var run = runs.insert(create(UUID.randomUUID(), "timeline", 1));
        var attempt = run.attempts().getFirst();
        String prefix = "/internal/v1/runs/selectAttemptEvents?attemptId=" + attempt.id() + "&runId=";
        assertThat(http("GET", prefix + run.id(), null, null).statusCode()).isEqualTo(401);
        assertThat(http("GET", prefix + UUID.randomUUID(), null, "test-control-token").statusCode()).isEqualTo(404);
        var pending = json.readTree(http("GET", prefix + run.id(), null, "test-control-token").body());
        assertThat(pending.path("workerStatus").asString()).isEqualTo("NOT_ASSIGNED");
        verifyNoInteractions(workerEvents);
        runs.dispatchPending();
        for (int i = 0; i < 101; i++) {
            mapper.appendAttemptEvent(run.id(), attempt.id(), "worker-default", attempt.leaseVersion(),
                    "LEASE_RENEWED", Instant.now(), i, "RUNNING", null, null);
        }
        when(workerEvents.select(eq(run.id()), eq(attempt.id()), eq("worker-default"), anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw mock(feign.FeignException.class);
        });
        var response = http("GET", prefix + run.id(), null, "test-control-token");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var timeline = json.readTree(response.body());
        assertThat(timeline.path("workerStatus").asString()).isEqualTo("UNAVAILABLE");
        assertThat(timeline.path("worker").path("events").size()).isZero();
        assertThat(timeline.path("scheduler").path("truncated").asBoolean()).isTrue();
        assertThat(timeline.path("scheduler").path("events").size()).isEqualTo(100);
        assertThat(timeline.path("scheduler").path("events").get(0).path("sequenceNumber").asInt()).isEqualTo(100);
    }

    @Test void logQueryVerifiesOwnershipAndUsesTheBoundWorkerOutsideTransactions() throws Exception {
        var run = runs.insert(create(UUID.randomUUID(), "attempt-logs", 1));
        var attemptId = run.attempts().getFirst().id();
        String prefix = "/internal/v1/runs/selectAttemptLogs?attemptId=" + attemptId + "&offset=100&runId=";
        assertThat(http("GET", prefix + UUID.randomUUID(), null, "test-control-token").statusCode()).isEqualTo(404);
        var waiting = json.readTree(http("GET", prefix + run.id(), null, "test-control-token").body());
        assertThat(waiting.path("workerStatus").asString()).isEqualTo("NOT_ASSIGNED");
        verifyNoInteractions(workerEvents);
        runs.dispatchPending();
        when(workerEvents.selectLogs(eq(run.id()), eq(attemptId), eq(100), eq("worker-default"), anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.taskflow.scheduler.execution.dto.AttemptLogs.WorkerPage(run.id(), attemptId, "worker-default",
                    java.util.List.of(new com.taskflow.scheduler.execution.dto.AttemptLogs.Entry(
                            run.id(), attemptId, Instant.now(), "INFO", "batch done")), null);
        });
        var loaded = http("GET", prefix + run.id(), null, "test-control-token");
        assertThat(loaded.statusCode()).as(loaded.body()).isEqualTo(200);
        assertThat(json.readTree(loaded.body()).path("entries").get(0).path("message").asString()).isEqualTo("batch done");
    }

    private java.util.List<String> eventTypes(UUID runId, UUID attemptId) {
        return jdbc.queryForList("SELECT event_type FROM scheduler_attempt_event WHERE run_id=? AND attempt_id=? ORDER BY id",
                String.class, runId.toString(), attemptId.toString());
    }
    private void makeDue() { jdbc.update("UPDATE run_attempt SET next_dispatch_at=?", Timestamp.from(Instant.now().minusSeconds(1))); }
    private Create create(UUID key, String name, int maxAttempts) {
        return new Create(key, new Snapshot(7, 2, name, "demoSuccess", json.readTree("{\"n\":1}"), 30, maxAttempts, 5,
                "MANUAL", null, "Asia/Shanghai", "SKIP", false, null));
    }
    private HttpResponse<String> http(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) builder.header("X-Taskflow-Token", token);
        builder.header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
