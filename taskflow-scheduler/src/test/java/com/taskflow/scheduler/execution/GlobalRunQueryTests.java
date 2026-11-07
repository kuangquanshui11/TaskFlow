package com.taskflow.scheduler.execution;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.taskflow.scheduler.execution.dto.RunQuery;
import com.taskflow.scheduler.execution.service.IRunService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class GlobalRunQueryTests {
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    final Instant from = Instant.parse("2026-11-01T00:00:00Z"), to = from.plus(Duration.ofDays(1));
    long scheduleSequence;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM dispatch_outbox"); jdbc.update("DELETE FROM run_attempt"); jdbc.update("DELETE FROM run_execution");
    }
    @Test void allConditionsIntersectWithoutDependingOnCurrentJobOrSnapshot() {
        var wanted = seed(7, "FAILED", "CRON", from);
        seed(8, "FAILED", "CRON", from); seed(7, "SUCCEEDED", "CRON", from); seed(7, "FAILED", "MANUAL", from);
        var result = runs.selectGlobal(query(7L, "FAILED", "CRON", null, 1, 20));
        assertThat(result.total()).isEqualTo(1);
        var row = result.records().getFirst();
        assertThat(row.id()).isEqualTo(wanted); assertThat(row.jobId()).isEqualTo(7);
        assertThat(row.attemptCount()).isEqualTo(2); assertThat(row.resultCode()).isEqualTo("EXAMPLE");
        assertThat(runs.selectGlobal(query(8L, "FAILED", "CRON", wanted, 1, 20)).total()).isZero();
        assertThat(runs.selectGlobal(query(null, "SUCCEEDED", null, wanted, 1, 20)).total()).isZero();
        assertThat(runs.selectGlobal(query(null, null, "MANUAL", wanted, 1, 20)).total()).isZero();
        assertThat(runs.selectGlobal(query(null, null, "CRON", null, 1, 20)).total()).isEqualTo(3);
        assertThat(runs.select(7, 1, 10).total()).isEqualTo(3);
    }
    @Test void halfOpenWindowPreservesFractionalMillisecondBoundaries() {
        seed(7, "READY", "MANUAL", from.minusMillis(1));
        var lower = seed(7, "READY", "MANUAL", from);
        var upper = seed(7, "READY", "MANUAL", to);
        seed(7, "READY", "MANUAL", to.plusMillis(1));
        assertThat(runs.selectGlobal(query(null, null, null, null, 1, 20)).records()).extracting(r -> r.id()).containsExactly(lower);
        var shifted = new RunQuery(from.plusNanos(1).toString(), to.plusNanos(1).toString(), null, null, null, null, null, 1, 20);
        assertThat(runs.selectGlobal(shifted).records()).extracting(r -> r.id()).containsExactly(upper);
    }
    @Test void deterministicPaginationAndCountUseIdenticalFilters() {
        var a = seed(7, "FAILED", "MANUAL", from); var b = seed(7, "FAILED", "MANUAL", from);
        var newest = seed(7, "FAILED", "MANUAL", from.plusMillis(1));
        seed(7, "SUCCEEDED", "MANUAL", from.plusMillis(2));
        var expected = new ArrayList<>(List.of(a, b)); expected.sort(Comparator.comparing(UUID::toString).reversed()); expected.addFirst(newest);
        var first = runs.selectGlobal(query(null, "FAILED", null, null, 1, 2));
        var second = runs.selectGlobal(query(null, "FAILED", null, null, 2, 2));
        assertThat(first.total()).isEqualTo(3); assertThat(second.total()).isEqualTo(3);
        var ids = new ArrayList<UUID>(); first.records().forEach(r -> ids.add(r.id())); second.records().forEach(r -> ids.add(r.id()));
        assertThat(ids).containsExactlyElementsOf(expected);
        assertThat(runs.selectGlobal(query(null, "FAILED", null, null, 3, 2)).records()).isEmpty();
    }
    @Test void defaultWindowAndExactHistoricalIdHaveDifferentScopes() {
        var old = seed(7, "FAILED", "MANUAL", from.minus(Duration.ofDays(100)));
        var current = seed(7, "SUCCEEDED", "MANUAL", Instant.now().minusSeconds(60));
        assertThat(runs.selectGlobal(new RunQuery(null, null, null, null, null, null, null, 1, 20)).records()).extracting(r -> r.id()).containsExactly(current);
        assertThat(runs.selectGlobal(new RunQuery(null, null, null, null, null, old, null, 1, 20)).records()).extracting(r -> r.id()).containsExactly(old);
        assertThat(runs.selectGlobal(query(null, null, null, old, 1, 20)).records()).isEmpty();
        assertThat(runs.selectGlobal(new RunQuery(null, null, null, null, null, UUID.randomUUID(), null, 1, 20)).total()).isZero();
    }
    @Test void failureGroupAndResultCodeFilterUseTheSameNormalization() {
        long job = Long.MAX_VALUE;
        var nullCode = seed(job, "FAILED", "MANUAL", from);
        var emptyCode = seed(job, "FAILED", "MANUAL", from.plusMillis(1));
        var unknownCode = seed(job, "FAILED", "MANUAL", from.plusMillis(2));
        var otherCode = seed(job, "FAILED", "MANUAL", from.plusMillis(3));
        seed(7, "FAILED", "MANUAL", from);
        seed(job, "SUCCEEDED", "MANUAL", from);
        jdbc.update("UPDATE run_execution SET result_code=NULL WHERE id=?", nullCode.toString());
        jdbc.update("UPDATE run_execution SET result_code='' WHERE id=?", emptyCode.toString());
        jdbc.update("UPDATE run_execution SET result_code='UNKNOWN' WHERE id=?", unknownCode.toString());

        var summary = runs.selectSummary(from, to);
        var group = summary.failures().getFirst();
        assertThat(group.jobId()).isEqualTo(job);
        assertThat(group.resultCode()).isEqualTo("UNKNOWN");
        assertThat(json.valueToTree(group).path("jobIdText").asString()).isEqualTo(Long.toString(job));
        var filter = new RunQuery(from.toString(), to.toString(), job, "FAILED", null, null, group.resultCode(), 1, 2);
        var filtered = runs.selectGlobal(filter);
        assertThat(filtered.total()).isEqualTo(group.runCount()).isEqualTo(3);
        assertThat(filtered.records()).extracting(r -> r.id()).containsExactly(unknownCode, emptyCode);
        assertThat(runs.selectGlobal(new RunQuery(from.toString(), to.toString(), job, "FAILED", null, null, "EXAMPLE", 1, 20))
                .records()).extracting(r -> r.id()).containsExactly(otherCode);
    }
    @Test void httpRejectsInvalidFiltersWindowsAndPaging() throws Exception {
        for (var query : List.of("?jobId=0", "?jobId=x", "?state=TIMED_OUT", "?state=failed", "?triggerType=HTTP", "?runId=broken",
                "?resultCode=", "?resultCode=" + "x".repeat(65),
                "?page=0", "?page=10001", "?pageSize=101", "?pageSize=0", "?from=" + from, "?to=" + to,
                "?from=bad&to=bad", "?from=" + to + "&to=" + from,
                "?from=" + from + "&to=" + from, "?from=" + from + "&to=" + from.plus(Duration.ofDays(7)).plusMillis(1),
                "?from=2026-11-01T00:00:00&to=" + to,
                "?from=%2B500000000-01-01T00:00:00Z&to=%2B500000000-01-02T00:00:00Z"))
            assertThat(http(query, true).statusCode()).as(query).isEqualTo(400);
        assertThat(http("", false).statusCode()).isEqualTo(401);
        assertThat(http("?from=" + from + "&to=" + from.plus(Duration.ofDays(7)) + "&page=10000&pageSize=100", true).statusCode()).isEqualTo(200);
    }
    @Test void httpReturnsPageWithoutExecutionSnapshotAndDoesNotMutateState() throws Exception {
        var id = seed(7, "FAILED", "CRON", from);
        var response = http("?runId=" + id + "&state=FAILED&triggerType=CRON&jobId=7", true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("pageSize").intValue()).isEqualTo(20);
        assertThat(data.path("total").longValue()).isEqualTo(1);
        assertThat(data.path("records").get(0).has("snapshot")).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM run_execution WHERE id=?", String.class, id.toString())).isEqualTo("FAILED");
    }
    private RunQuery query(Long jobId, String state, String trigger, UUID runId, int page, int size) {
        return new RunQuery(from.toString(), to.toString(), jobId, state, trigger, runId, null, page, size);
    }
    private UUID seed(long job, String state, String trigger, Instant created) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_execution(id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,result_code,message,trigger_type,scheduled_at) VALUES (?,?,1,?,'test',?,'{}',2,?,?,'EXAMPLE','example',?,?)",
                id.toString(), job, UUID.randomUUID().toString(), state, Timestamp.from(created), Timestamp.from(created), trigger,
                "CRON".equals(trigger) ? Timestamp.from(created.plusMillis(++scheduleSequence)) : null);
        return id;
    }
    private HttpResponse<String> http(String query, boolean token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/runs/selectGlobal" + query));
        if (token) request.header("X-Taskflow-Token", "test-control-token");
        try (var client = HttpClient.newHttpClient()) { return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
