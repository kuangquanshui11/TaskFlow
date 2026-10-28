package com.taskflow.scheduler.execution;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.service.IRunService;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean WorkerClient worker;
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
    @Test void creationRollsBackIfFirstAttemptCannotBeStored() {
        doThrow(new IllegalStateException("injected")).when(mapper).insertAttempt(any());
        assertThatThrownBy(() -> runs.insert(create(UUID.randomUUID(), "rollback", 1))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution", Long.class)).isZero();
    }
    @Test void callbackBeforeDispatchResponseCannotRegressTerminalState() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Dispatch dispatch = call.getArgument(0);
            runs.updateResult(new Result(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "ok", false));
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "fast", 1));
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(runs.selectById(run.id()).attempts()).hasSize(1);
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
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), 99, "SUCCEEDED", "SUCCEEDED", "ok", false)))
                .isInstanceOf(RunException.class);
        var result = new Result(attempt.id(), run.id(), attempt.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "ok", false);
        runs.updateResult(result); runs.updateResult(result);
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(), "FAILED", "HANDLER_FAILED", "bad", true)))
                .isInstanceOf(RunException.class);
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void businessRetryCreatesNewAttemptWithinTheSameRunAndHonorsDelay() {
        doAnswer(call -> {
            Dispatch dispatch = call.getArgument(0);
            boolean first = dispatch.attemptNumber() == 1;
            runs.updateResult(new Result(dispatch.attemptId(), dispatch.runId(), dispatch.leaseVersion(),
                    first ? "FAILED" : "SUCCEEDED", first ? "HANDLER_FAILED" : "SUCCEEDED", first ? "fail" : "ok", first));
            return null;
        }).when(worker).dispatch(any());
        var run = runs.insert(create(UUID.randomUUID(), "business-retry", 2));
        runs.dispatchPending();
        var waiting = runs.selectById(run.id());
        assertThat(waiting.state()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.attempts()).hasSize(2);
        runs.dispatchPending();
        verify(worker, times(1)).dispatch(any());
        makeDue(); runs.dispatchPending();
        assertThat(runs.selectById(run.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(runs.selectById(run.id()).attempts().get(1).id()).isNotEqualTo(waiting.attempts().getFirst().id());
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
    @Test void leaseExpiryIsTerminalAndRejectsLateWorkerResult() {
        var run = runs.insert(create(UUID.randomUUID(), "expiry", 3));
        runs.dispatchPending();
        var attempt = runs.selectById(run.id()).attempts().getFirst();
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), attempt.id().toString());
        runs.dispatchPending();
        assertThat(runs.selectById(run.id()).attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        assertThatThrownBy(() -> runs.updateResult(new Result(attempt.id(), run.id(), attempt.leaseVersion(), "SUCCEEDED", "SUCCEEDED", "late", false)))
                .isInstanceOf(RunException.class);
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
