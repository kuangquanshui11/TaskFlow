package com.taskflow.admin.job;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(resolver = JobApiTests.DatabaseProfiles.class)
class JobApiTests {
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    static class DatabaseProfiles implements ActiveProfilesResolver {
        @Override
        public String[] resolve(Class<?> testClass) {
            String url = System.getenv("TASKFLOW_TEST_DB_URL");
            if (url == null || url.isBlank()) {
                return new String[]{"test"};
            }
            requireIsolatedMySql(url);
            return new String[]{"test", "test-mysql"};
        }
    }

    private static void requireIsolatedMySql(String url) {
        if (!url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/taskflow_it_[A-Za-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("MySQL 测试只能连接本机 taskflow_it_ 前缀的专用数据库");
        }
    }

    @BeforeEach
    void cleanIsolatedDatabase() throws SQLException {
        // 在实际连接上再次检查，防止配置优先级变化将清理语句指向用户库。
        try (var connection = jdbc.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL();
            if (!url.startsWith("jdbc:h2:mem:taskflow-admin-")) {
                requireIsolatedMySql(url);
            }
        }
        jdbc.update("DELETE FROM job_definition");
    }

    @Test
    void insertAndSelectRoundTripPreservesJsonAndDefaults() throws Exception {
        var response = request("POST", "/api/v1/jobs/insert", input("订单检查"));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = tree(response);
        var data = body.get("data");
        assertThat(body.get("code").stringValue()).isEqualTo("OK");
        assertThat(body.get("requestId").stringValue()).isNotBlank();
        assertThat(data.get("payload").get("batchSize").intValue()).isEqualTo(100);
        assertThat(data.get("scheduleType").stringValue()).isEqualTo("MANUAL");
        assertThat(data.get("maxAttempts").intValue()).isEqualTo(3);
        assertThat(data.get("enabled").booleanValue()).isFalse();
        assertThat(data.get("createdAt").stringValue()).endsWith("Z");

        var selected = request("GET", "/api/v1/jobs/selectById?id=" + data.get("id").longValue(), null);
        assertThat(selected.statusCode()).isEqualTo(200);
        assertThat(tree(selected).get("data")).isEqualTo(data);
    }

    @Test
    void selectSupportsPaginationAndCombinedFilters() throws Exception {
        create("订单 A");
        var second = create("订单 B");
        create("其他任务");
        request("POST", "/api/v1/jobs/enable", mutation(second));
        String keyword = URLEncoder.encode("订单", StandardCharsets.UTF_8);

        var page = tree(request("GET", "/api/v1/jobs/select?keyword=" + keyword
                + "&page=1&pageSize=1", null)).get("data");
        assertThat(page.get("total").longValue()).isEqualTo(2);
        assertThat(page.get("records").size()).isEqualTo(1);
        assertThat(page.get("records").get(0).get("id").longValue()).isEqualTo(second.get("id").longValue());

        var filtered = tree(request("GET", "/api/v1/jobs/select?keyword=" + keyword
                + "&enabled=true&handlerKey=demoSuccess", null)).get("data");
        assertThat(filtered.get("total").longValue()).isEqualTo(1);
    }

    @Test
    void searchTreatsSqlWildcardsAsLiteralCharacters() throws Exception {
        create("rate_100%");
        create("rateX100anything");
        String keyword = URLEncoder.encode("_100%", StandardCharsets.UTF_8);
        var result = tree(request("GET", "/api/v1/jobs/select?keyword=" + keyword, null)).get("data");
        assertThat(result.get("total").longValue()).isEqualTo(1);
        assertThat(result.get("records").get(0).get("name").stringValue()).isEqualTo("rate_100%");
        String injection = URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8);
        assertThat(tree(request("GET", "/api/v1/jobs/select?keyword=" + injection, null))
                .get("data").get("total").longValue()).isZero();
    }

    @Test
    void updateIncrementsVersionAndRejectsStaleWrite() throws Exception {
        var job = create("旧名称");
        var update = Map.of("id", job.get("id").longValue(), "configVersion", 1, "job", input("新名称"));
        var changed = request("POST", "/api/v1/jobs/update", update);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(tree(changed).get("data").get("configVersion").longValue()).isEqualTo(2);
        assertThat(tree(changed).get("data").get("name").stringValue()).isEqualTo("新名称");

        var stale = request("POST", "/api/v1/jobs/update", update);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(tree(stale).get("code").stringValue()).isEqualTo("JOB_VERSION_CONFLICT");
    }

    @Test
    void replacingCronWithManualClearsNullableFieldsInDatabase() throws Exception {
        var input = new LinkedHashMap<>(input("定时改手动"));
        input.put("description", "需要清空的说明");
        input.put("scheduleType", "CRON");
        input.put("cronExpression", "0 */5 * * * *");
        var job = tree(request("POST", "/api/v1/jobs/insert", input)).get("data");
        var replacement = Map.of("name", "手动任务", "handlerKey", "demoSuccess");
        var response = request("POST", "/api/v1/jobs/update", Map.of(
                "id", job.get("id").longValue(), "configVersion", 1, "job", replacement));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var updated = tree(response).get("data");
        assertThat(updated.get("scheduleType").stringValue()).isEqualTo("MANUAL");
        for (String field : List.of("description", "payload", "cronExpression")) {
            assertThat(updated.get(field).isNull()).as(field).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_definition WHERE id = ?
                AND description IS NULL AND payload_json IS NULL AND cron_expression IS NULL
                """, Long.class, job.get("id").longValue())).isEqualTo(1L);
    }

    @Test
    void paginationPreservesTotalsOnLaterAndOutOfRangePages() throws Exception {
        var summary = tree(request("GET", "/api/v1/jobs/selectSummary", null)).get("data");
        for (String field : List.of("total", "enabled", "disabled", "manual", "cron")) {
            assertThat(summary.get(field).longValue()).as(field).isZero();
        }
        var first = create("第一页之前的记录");
        create("中间记录");
        create("最新记录");
        var later = tree(request("GET", "/api/v1/jobs/select?page=2&pageSize=2", null)).get("data");
        assertThat(later.get("total").longValue()).isEqualTo(3);
        assertThat(later.get("records").size()).isEqualTo(1);
        assertThat(later.get("records").get(0).get("id").longValue()).isEqualTo(first.get("id").longValue());
        var overflow = tree(request("GET", "/api/v1/jobs/select?page=1000000&pageSize=100", null)).get("data");
        assertThat(overflow.get("total").longValue()).isEqualTo(3);
        assertThat(overflow.get("records").size()).isZero();
    }

    @Test
    void nestedJsonAndEscapedTextSurviveInsertAndReplacement() throws Exception {
        var payload = Map.of("nested", Map.of("text", "中文 \"引号\" \\ 路径", "flags", List.of(true, false)),
                "items", List.of(1, 2, 3));
        var input = new LinkedHashMap<>(input("JSON 参数"));
        input.put("payload", payload);
        var inserted = request("POST", "/api/v1/jobs/insert", input);
        assertThat(inserted.statusCode()).as(inserted.body()).isEqualTo(201);
        var job = tree(inserted).get("data");
        assertThat(job.get("payload")).isEqualTo(json.readTree(json.writeValueAsString(payload)));
        input.put("payload", Map.of("changed", List.of("新参数", "!_%")));
        var changed = request("POST", "/api/v1/jobs/update", Map.of(
                "id", job.get("id").longValue(), "configVersion", 1, "job", input));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).get("data").get("payload"))
                .isEqualTo(json.readTree(json.writeValueAsString(input.get("payload"))));
    }

    @Test
    void concurrentWritersOnlyOneCanUseTheSameVersion() throws Exception {
        var job = create("并发修改");
        try (var client = HttpClient.newHttpClient()) {
            var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int index = 0; index < 2; index++) {
                var payload = Map.of("id", job.get("id").longValue(), "configVersion", 1,
                        "job", input("并发修改 " + index));
                calls.add(client.sendAsync(http("POST", "/api/v1/jobs/update", payload),
                        HttpResponse.BodyHandlers.ofString()));
            }
            List<Integer> statuses = calls.stream().map(CompletableFuture::join)
                    .map(HttpResponse::statusCode).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void enableDisableAndSoftDeletePreserveTombstone() throws Exception {
        var job = create("生命周期");
        var enabled = tree(request("POST", "/api/v1/jobs/enable", mutation(job))).get("data");
        assertThat(enabled.get("enabled").booleanValue()).isTrue();
        var disabled = tree(request("POST", "/api/v1/jobs/disable", mutation(enabled))).get("data");
        assertThat(disabled.get("enabled").booleanValue()).isFalse();
        assertThat(request("POST", "/api/v1/jobs/delete", mutation(job)).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/v1/jobs/delete", mutation(disabled)).statusCode()).isEqualTo(200);

        long id = job.get("id").longValue();
        assertThat(request("GET", "/api/v1/jobs/selectById?id=" + id, null).statusCode()).isEqualTo(404);
        assertThat(tree(request("GET", "/api/v1/jobs/select", null))
                .get("data").get("total").longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_definition WHERE id = ? AND deleted_at IS NOT NULL",
                Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT config_version FROM job_definition WHERE id = ?",
                Long.class, id)).isEqualTo(4L);
        assertThat(request("POST", "/api/v1/jobs/update", Map.of(
                "id", id, "configVersion", 4, "job", input("试图恢复"))).statusCode()).isEqualTo(404);
        for (String action : List.of("enable", "disable", "delete")) {
            assertThat(request("POST", "/api/v1/jobs/" + action,
                    Map.of("id", id, "configVersion", 4)).statusCode()).as(action).isEqualTo(404);
        }
    }

    @Test
    void summaryCountsRealConfigurationAndExcludesDeletedJobs() throws Exception {
        var manual = create("手动");
        var cron = new LinkedHashMap<>(input("定时"));
        cron.put("scheduleType", "CRON");
        cron.put("cronExpression", "0 */5 * * * *");
        cron.put("enabled", true);
        request("POST", "/api/v1/jobs/insert", cron);
        request("POST", "/api/v1/jobs/delete", mutation(manual));
        var summary = tree(request("GET", "/api/v1/jobs/selectSummary", null)).get("data");
        assertThat(summary.get("total").longValue()).isEqualTo(1);
        assertThat(summary.get("enabled").longValue()).isEqualTo(1);
        assertThat(summary.get("manual").longValue()).isZero();
        assertThat(summary.get("cron").longValue()).isEqualTo(1);
    }

    @Test
    void previewUsesConfiguredTimeZoneAndDoesNotCreateJobs() throws Exception {
        var body = Map.of("cronExpression", "0 0 9 * * *", "timeZone", "Asia/Shanghai",
                "from", "2026-10-26T00:00:00Z", "count", 2);
        var response = request("POST", "/api/v1/jobs/preview", body);
        assertThat(response.statusCode()).isEqualTo(200);
        var times = tree(response).get("data").get("nextFireTimes");
        assertThat(times.get(0).stringValue()).isEqualTo("2026-10-26T01:00:00Z");
        assertThat(times.get(1).stringValue()).isEqualTo("2026-10-27T01:00:00Z");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_definition", Long.class)).isZero();
    }

    @Test
    void previewRejectsImpossibleSchedulesAndOutOfRangeDates() throws Exception {
        assertThat(request("POST", "/api/v1/jobs/preview", Map.of(
                "cronExpression", "0 0 0 31 2 *", "from", "2026-10-26T00:00:00Z"))
                .statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/v1/jobs/preview", Map.of(
                "cronExpression", "* * * * * *", "from", "+1000000000-12-31T23:59:59.999999999Z"))
                .statusCode()).isEqualTo(400);
    }

    @Test
    void invalidBusinessInputsDoNotPersistJobs() throws Exception {
        var cases = new ArrayList<Map<String, Object>>();
        var badHandler = new LinkedHashMap<>(input("不合法处理器"));
        badHandler.put("handlerKey", "notConfigured");
        cases.add(badHandler);
        var badCron = new LinkedHashMap<>(input("不合法 Cron"));
        badCron.put("scheduleType", "CRON");
        badCron.put("cronExpression", "invalid");
        cases.add(badCron);
        var badZone = new LinkedHashMap<>(input("不合法时区"));
        badZone.put("timeZone", "Invalid/Zone");
        cases.add(badZone);
        var badManual = new LinkedHashMap<>(input("手动任务带 Cron"));
        badManual.put("cronExpression", "* * * * * *");
        cases.add(badManual);
        var badPayload = new LinkedHashMap<>(input("非对象参数"));
        badPayload.put("payload", List.of(1, 2));
        cases.add(badPayload);
        var oversize = new LinkedHashMap<>(input("超大参数"));
        oversize.put("payload", Map.of("text", "a".repeat(65536)));
        cases.add(oversize);

        for (var body : cases) {
            assertThat(request("POST", "/api/v1/jobs/insert", body).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_definition", Long.class)).isZero();
    }

    @Test
    void invalidFieldsAndPaginationReturnClientErrors() throws Exception {
        var bad = new LinkedHashMap<>(input(" "));
        bad.put("maxAttempts", 0);
        var response = request("POST", "/api/v1/jobs/insert", bad);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(tree(response).get("data").has("name")).isTrue();
        assertThat(request("GET", "/api/v1/jobs/select?page=0&pageSize=101", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/v1/jobs/select?page=abc", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/v1/jobs/selectById?id=-1", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/v1/jobs/selectById", null).statusCode()).isEqualTo(400);
    }

    @Test
    void unsupportedMethodsMalformedJsonAndUnknownEndpointsAreConsistent() throws Exception {
        assertThat(request("GET", "/api/v1/jobs/insert", null).statusCode()).isEqualTo(405);
        assertThat(request("GET", "/api/v1/jobs/missing", null).statusCode()).isEqualTo(404);
        try (var client = HttpClient.newHttpClient()) {
            var badJson = HttpRequest.newBuilder(URI.create(url("/api/v1/jobs/insert")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{")).build();
            var response = client.send(badJson, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(tree(response).get("code").stringValue()).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(request("GET", "/api/v1/jobs/selectById?id=999999999", null).statusCode()).isEqualTo(404);
    }

    @Test
    void handlerListIsTheConfiguredAllowlistAndRequestsAreTraceable() throws Exception {
        var response = request("GET", "/api/v1/job-handlers/select", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(tree(response).get("data").toString()).contains("demoSuccess");
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("job-api-test");
        assertThat(tree(response).get("requestId").stringValue()).isEqualTo("job-api-test");
    }

    private Map<String, Object> input(String name) {
        return Map.of("name", name, "handlerKey", "demoSuccess", "payload", Map.of("batchSize", 100));
    }

    private Map<String, Object> mutation(JsonNode job) {
        return Map.of("id", job.get("id").longValue(), "configVersion", job.get("configVersion").longValue());
    }

    private JsonNode create(String name) throws Exception {
        var response = request("POST", "/api/v1/jobs/insert", input(name));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return tree(response).get("data");
    }

    private HttpResponse<String> request(String method, String path, Object payload) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(http(method, path, payload), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpRequest http(String method, String path, Object payload) {
        var request = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(10))
                .header("X-Request-Id", "job-api-test");
        if (payload == null) {
            return request.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
    }

    private JsonNode tree(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
