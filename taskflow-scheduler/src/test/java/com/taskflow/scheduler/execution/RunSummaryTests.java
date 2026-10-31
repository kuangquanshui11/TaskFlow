package com.taskflow.scheduler.execution;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class RunSummaryTests {
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean WorkerClient worker;
    final Instant from = Instant.parse("2026-10-30T00:00:00Z"), to = from.plus(Duration.ofDays(1));
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
    }
    @Test void emptyAndDefaultWindow() throws Exception {
        var summary = runs.selectSummary(null, null);
        assertThat(Duration.between(summary.from(), summary.to())).isEqualTo(Duration.ofHours(24));
        assertThat(summary.total()).isZero(); assertThat(summary.successRate()).isNull();
        assertThat(summary.failures()).isEmpty(); assertThat(summary.latency().p95Millis()).isNull();
        assertThat(summary.stateCounts()).hasSize(5).allSatisfy((k, v) -> assertThat(v).isZero());
        var response = http("", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).has("successRate")).isTrue();
        assertThat(json.readTree(response.body()).path("successRate").isNull()).isTrue();
    }
    @Test void countsAttemptsRoundingAndNearestRank() {
        seed("READY", 1, null, from, null); seed("RUNNING", 1, null, from, null);
        seed("RETRY_WAIT", 1, null, from, null);
        var a = seed("SUCCEEDED", 1, null, from, 100L);
        seed("SUCCEEDED", 1, null, from, 200L); seed("FAILED", 2, "BAD", from, 900L);
        jdbc.update("INSERT INTO run_attempt(id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at) VALUES(?,?,2,2,'SUCCEEDED',0,?)", UUID.randomUUID().toString(), a, Timestamp.from(from));
        var result = runs.selectSummary(from, to);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.stateCounts()).containsEntry("READY", 1L).containsEntry("RUNNING", 1L)
                .containsEntry("RETRY_WAIT", 1L).containsEntry("SUCCEEDED", 2L).containsEntry("FAILED", 1L);
        assertThat(result.finished()).isEqualTo(3); assertThat(result.succeeded()).isEqualTo(2); assertThat(result.failed()).isEqualTo(1);
        assertThat(result.successRate()).isEqualByComparingTo("0.6667");
        assertThat(result.attemptTotal()).isEqualTo(7); assertThat(result.retriedRunCount()).isEqualTo(1);
        assertThat(result.latency().avgMillis()).isEqualTo(400); assertThat(result.latency().p95Millis()).isEqualTo(900);
        assertThat(result.latency().sampled()).isEqualTo(3); assertThat(result.latency().truncated()).isFalse();
    }
    @Test void windowIncludesFromAndExcludesToAndOutsideAttempts() {
        seed("READY", 1, null, from.minusMillis(1), null); seed("READY", 1, null, from, null);
        seed("FAILED", 2, "BAD", to, 1L); seed("READY", 1, null, to.plusMillis(1), null);
        var result = runs.selectSummary(from, to);
        assertThat(result.total()).isEqualTo(1); assertThat(result.attemptTotal()).isEqualTo(1); assertThat(result.failures()).isEmpty();
        var fractional = runs.selectSummary(from.plusNanos(1), to.plusNanos(1));
        assertThat(fractional.total()).isEqualTo(1); assertThat(fractional.failed()).isEqualTo(1);
        assertThat(fractional.attemptTotal()).isEqualTo(1); assertThat(fractional.failures()).hasSize(1);
    }
    @Test void stateFlowKeepsFirstStartAndOnlyTerminalFinish() {
        var run = runs.insert(new Create(UUID.randomUUID(), new Snapshot(7, 2, "flow", "demoSuccess", null,
                30, 2, 1, "MANUAL", null, "UTC", "SKIP", false, null)));
        assertThat(time(run.id(), "started_at")).isNull(); assertThat(time(run.id(), "finished_at")).isNull();
        runs.dispatchPending(); var started = time(run.id(), "started_at"); assertThat(started).isNotNull();
        var first = runs.selectById(run.id()).attempts().getFirst();
        runs.updateResult(new Result(first.id(), run.id(), first.leaseVersion(), "FAILED", "BAD", "retry", true,
                "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RETRY_WAIT");
        assertThat(time(run.id(), "started_at")).isEqualTo(started); assertThat(time(run.id(), "finished_at")).isNull();
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=? WHERE run_id=?", Timestamp.from(Instant.now().minusSeconds(1)), run.id().toString());
        runs.dispatchPending(); assertThat(time(run.id(), "started_at")).isEqualTo(started);
        var second = runs.selectById(run.id()).attempts().getLast();
        var result = new Result(second.id(), run.id(), second.leaseVersion(), "SUCCEEDED", "OK", "done", false,
                "worker-default", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        runs.updateResult(result); var finished = time(run.id(), "finished_at"); assertThat(finished).isNotNull();
        runs.updateResult(result); assertThat(time(run.id(), "finished_at")).isEqualTo(finished);
        assertThat(time(run.id(), "started_at")).isEqualTo(started);
    }
    @Test void failuresGroupByJobAndCodeWithStableTopFive() {
        for (int job = 1; job <= 7; job++) for (int n = 0; n < job; n++) seed("FAILED", job, job == 7 ? null : "BAD", from.plusSeconds(n), 1L);
        seed("FAILED", 7, "", from.plusSeconds(20), 1L); seed("FAILED", 7, "OTHER", from, 1L);
        var groups = runs.selectSummary(from, to).failures();
        assertThat(groups).hasSize(5); assertThat(groups.getFirst().jobId()).isEqualTo(7);
        assertThat(groups.getFirst().resultCode()).isEqualTo("UNKNOWN"); assertThat(groups.getFirst().runCount()).isEqualTo(8);
        assertThat(groups.getFirst().lastFailedAt()).isEqualTo(from.plusSeconds(20).plusMillis(1));
        assertThat(groups.stream().map(g -> g.runCount()).toList()).containsExactly(8L, 6L, 5L, 4L, 3L);
    }
    @Test void p95CapsEarliestSampleAndAverageUsesFullWindow() {
        var args = new ArrayList<Object[]>();
        for (int i = 0; i < 10001; i++) args.add(new Object[]{UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Timestamp.from(from.plusMillis(i)), Timestamp.from(from), Timestamp.from(from.plusMillis(i == 10000 ? 100000 : 10))});
        jdbc.batchUpdate("INSERT INTO run_execution(id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,started_at,finished_at) VALUES (?,1,1,?,'test','SUCCEEDED','{}',1,?,CURRENT_TIMESTAMP(3),?,?)", args);
        var latency = runs.selectSummary(from, to).latency();
        assertThat(latency.sampled()).isEqualTo(10000); assertThat(latency.truncated()).isTrue();
        assertThat(latency.p95Millis()).isEqualTo(10); assertThat(latency.avgMillis()).isEqualTo(20);
    }
    @Test void invalidWindowsAndMissingControlKey() throws Exception {
        for (var query : List.of("?from=" + from, "?to=" + to, "?from=bad&to=" + to,
                "?from=" + to + "&to=" + from, "?from=" + from + "&to=" + from,
                "?from=" + from + "&to=" + from.plus(Duration.ofDays(7)).plusMillis(1),
                "?from=2026-10-30T00:00:00&to=" + to)) {
            var response = http(query, true); assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").stringValue()).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(http("", false).statusCode()).isEqualTo(401);
        assertThat(http("?from=" + from + "&to=" + from.plus(Duration.ofDays(7)), true).statusCode()).isEqualTo(200);
    }
    private Timestamp time(UUID id, String column) { return jdbc.queryForObject("SELECT " + column + " FROM run_execution WHERE id=?", Timestamp.class, id.toString()); }
    private String seed(String state, long job, String code, Instant created, Long millis) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO run_execution(id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,result_code,started_at,finished_at) VALUES (?,?,1,?,'test',?,'{}',1,?,?,?, ?,?)",
                id, job, UUID.randomUUID().toString(), state, Timestamp.from(created), Timestamp.from(created), code,
                millis == null ? null : Timestamp.from(created), millis == null ? null : Timestamp.from(created.plusMillis(millis)));
        jdbc.update("INSERT INTO run_attempt(id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at) VALUES(?,?,1,1,?,0,?)", UUID.randomUUID().toString(), id, state, Timestamp.from(created));
        return id;
    }
    private HttpResponse<String> http(String query, boolean token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/runs/selectSummary" + query));
        if (token) request.header("X-Taskflow-Token", "test-control-token");
        try (var client = HttpClient.newHttpClient()) { return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
