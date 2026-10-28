package com.taskflow.admin.jobsync;

import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.admin.job.model.JobDefinition.*;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.jobsync.client.PlanSyncClient;
import com.taskflow.admin.jobsync.mapper.JobSyncMapper;
import com.taskflow.admin.jobsync.model.JobSyncRecords.*;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
class JobSyncTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicInteger responseCode = new AtomicInteger(200);
    static final Queue<JsonNode> received = new ConcurrentLinkedQueue<>();
    static volatile boolean wrongAck;
    static volatile String queryReply;
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;
    static final HttpServer scheduler = server();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("taskflow.job-sync.scheduler-url", () -> "http://127.0.0.1:" + scheduler.getAddress().getPort());
    }
    @Autowired IJobService jobs;
    @Autowired IJobSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean JobSyncMapper mapper;
    @MockitoSpyBean PlanSyncClient client;
    @Value("${local.server.port}") int port;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM job_sync_outbox");
        jdbc.update("DELETE FROM job_definition");
        responseCode.set(200); received.clear(); wrongAck = false; entered = null; release = null;
        queryReply = JSON.writeValueAsString(Map.of("jobId",7,"configVersion",2,"enabled",true,"deleted",false,
                "calculationState","READY","automaticTriggerEnabled",false,"nextFireTime","2026-10-26T01:00:00Z","calculatedAt","2026-10-26T00:00:00Z",
                "updatedAt","2026-10-26T00:00:00Z","snapshotJson","{\"scheduleType\":\"CRON\",\"cronExpression\":\"0 0 9 * * *\",\"timeZone\":\"Asia/Shanghai\"}"));
    }
    @AfterAll static void stop() { scheduler.stop(0); }

    @Test void savesAllMutationsAsImmutableSnapshotsAndSendsOnlyOutsideTransaction() {
        var job = jobs.insert(config("first", false));
        job = jobs.update(job.id(), job.configVersion(), config("changed", false));
        job = jobs.changeEnabled(job.id(), job.configVersion(), true);
        job = jobs.changeEnabled(job.id(), job.configVersion(), false);
        jobs.delete(job.id(), job.configVersion());
        assertThat(received).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_sync_outbox", Long.class)).isEqualTo(5);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(client).send(any());
        sync.dispatchPending();
        var events = received.stream().sorted(Comparator.comparingLong(node -> node.get("configVersion").longValue())).toList();
        assertThat(events).hasSize(5);
        assertThat(events.getFirst().get("configuration").get("name").stringValue()).isEqualTo("first");
        assertThat(events.get(2).get("configuration").get("enabled").booleanValue()).isTrue();
        assertThat(events.getLast().get("deleted").booleanValue()).isTrue();
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("SYNCED");
        assertThat(sync.selectByJobId(job.id()).deleted()).isTrue();
    }

    @Test void outboxFailureRollsBackJobAndVersionChange() {
        doThrow(new IllegalStateException("injected")).when(mapper).insert(any());
        assertThatThrownBy(() -> jobs.insert(config("rollback", false))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_definition", Long.class)).isZero();
        reset(mapper);
        var job = jobs.insert(config("retained", false));
        doThrow(new IllegalStateException("injected")).when(mapper).insert(any());
        assertThatThrownBy(() -> jobs.changeEnabled(job.id(), 1, true)).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.selectById(job.id()).configVersion()).isEqualTo(1);
        assertThat(jobs.selectById(job.id()).configuration().enabled()).isFalse();
    }

    @Test void transportFailureRetriesSameEventWithBackoffAndStopsAtBudgetThenAllowsManualRetry() throws Exception {
        responseCode.set(503);
        var job = jobs.insert(config("offline", false));
        sync.dispatchPending();
        var first = sync.selectByJobId(job.id());
        assertThat(first.state()).isEqualTo("PENDING");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isAfter(Instant.now());
        sync.dispatchPending();
        assertThat(received).hasSize(1);
        for (int attempt = 1; attempt < 5; attempt++) { makeDue(); sync.dispatchPending(); }
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("FAILED");
        sync.dispatchPending();
        assertThat(received).hasSize(5);
        assertThat(received.stream().map(node -> node.get("eventId").stringValue()).distinct()).containsExactly(first.eventId().toString());
        assertThat(http("POST", "/api/v1/job-sync/updateRetry", Map.of("id",job.id(),"configVersion",1)).statusCode()).isEqualTo(200);
        assertThat(sync.selectByJobId(job.id()).attempts()).isZero();
        responseCode.set(200);
        // TIMESTAMP(3) 的就绪时间可能比 JVM 当前时间多一个舍入单位，显式推进夹具，避免依赖调用耗时。
        makeDue();
        sync.dispatchPending();
        var status = sync.selectByJobId(job.id());
        assertThat(status.state()).isEqualTo("SYNCED");
        assertThat(status.eventId()).isEqualTo(first.eventId());
        assertThat(status.lastErrorCode()).isNull();
    }

    @Test void rejectsFalseAcknowledgementsAndDoesNotRetryPermanentHttpErrors() {
        var job = jobs.insert(config("ack", false));
        wrongAck = true;
        sync.dispatchPending();
        assertThat(sync.selectByJobId(job.id()).lastErrorCode()).isEqualTo("SCHEDULER_PROTOCOL_ERROR");
        assertThat(sync.selectByJobId(job.id()).syncedAt()).isNull();
        wrongAck = false; responseCode.set(401); makeDue(); sync.dispatchPending();
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("FAILED");
        assertThat(sync.selectByJobId(job.id()).lastErrorCode()).isEqualTo("SCHEDULER_HTTP_401");
    }

    @Test void lateSuccessOfOldVersionNeverMarksLatestConfigurationSynced() {
        var job = jobs.insert(config("old", false));
        var now = Instant.now();
        var old = mapper.claim(sync.selectByJobId(job.id()).eventId(), UUID.randomUUID(), now, now.plusSeconds(30), 5).orElseThrow();
        jobs.update(job.id(), 1, config("new", true));
        mapper.acknowledge(old, new Acknowledgement(old.event().eventId(), job.id(), 1, 1, "APPLIED"), Instant.now());
        assertThat(sync.selectByJobId(job.id()).configVersion()).isEqualTo(2);
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("PENDING");
    }

    @Test void expiredLeaseIsRecoveredAndOldOwnerCannotOverwriteNewOutcome() {
        var job = jobs.insert(config("recovery", false));
        var id = sync.selectByJobId(job.id()).eventId();
        var now = Instant.now();
        var old = mapper.claim(id, UUID.randomUUID(), now, now.plusSeconds(30), 5).orElseThrow();
        jdbc.update("UPDATE job_sync_outbox SET lease_until=? WHERE event_id=?", Timestamp.from(now.minusSeconds(1)), id.toString());
        sync.dispatchPending();
        mapper.fail(old, "STALE_OWNER", false, Instant.now(), Instant.now());
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("SYNCED");
        assertThat(sync.selectByJobId(job.id()).attempts()).isEqualTo(2);
        jdbc.update("UPDATE job_sync_outbox SET state='SENDING',attempts=5,lease_until=? WHERE event_id=?",
                Timestamp.from(now.minusSeconds(1)), id.toString());
        sync.dispatchPending();
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("FAILED");
        assertThat(received).hasSize(1);
    }

    @Test void twoSendersCompeteForOneEventWhileSavingDoesNotWaitForNetwork() throws Exception {
        var job = jobs.insert(config("slow scheduler", false));
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sending = executor.submit(sync::dispatchPending);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                executor.submit(sync::dispatchPending).get(2, TimeUnit.SECONDS);
                var changed = executor.submit(() -> jobs.changeEnabled(job.id(), 1, true)).get(2, TimeUnit.SECONDS);
                assertThat(changed.configVersion()).isEqualTo(2);
                assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("PENDING");
                assertThat(received).hasSize(1);
            } finally { release.countDown(); }
            sending.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void statusApiIncludesDeletedJobsAndRejectsInvalidRetryAndParameters() throws Exception {
        var job = jobs.insert(config("delete", false));
        jobs.delete(job.id(), 1);
        var status = http("GET", "/api/v1/job-sync/selectByJobId?id=" + job.id(), null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(status.body()).get("data").get("deleted").booleanValue()).isTrue();
        assertThat(http("POST", "/api/v1/job-sync/updateRetry", Map.of("id",job.id(),"configVersion",1)).statusCode()).isEqualTo(409);
        assertThat(http("GET", "/api/v1/job-sync/selectByJobId?id=-1", null).statusCode()).isEqualTo(400);
        assertThat(http("GET", "/api/v1/job-sync/selectByJobId?id=999999", null).statusCode()).isEqualTo(404);
    }

    @Test void planQueryReturnsSchedulersVersionAndStoredTimeOutsideSqlTransaction() throws Exception {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(client).selectPlan(anyLong());
        var response = http("GET", "/api/v1/job-sync/selectPlanByJobId?id=7", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data = JSON.readTree(response.body()).get("data");
        assertThat(data.get("configVersion").longValue()).isEqualTo(2);
        assertThat(data.get("nextFireTime").stringValue()).isEqualTo("2026-10-26T01:00:00Z");
        assertThat(data.get("timeZone").stringValue()).isEqualTo("Asia/Shanghai");
        assertThat(data.get("automaticTriggerEnabled").booleanValue()).isFalse();
        assertThat(data.has("snapshotJson")).isFalse();
        var disabled = (tools.jackson.databind.node.ObjectNode) JSON.readTree(queryReply);
        disabled.put("calculationState", "DISABLED").put("enabled", false).putNull("nextFireTime");
        queryReply = JSON.writeValueAsString(disabled);
        assertThat(sync.selectPlanByJobId(7).nextFireTime()).isNull();
        assertThat(sync.selectPlanByJobId(7).calculationState()).isEqualTo("DISABLED");
    }

    @Test void planQueryReportsMissingUnavailableAndMalformedDataInsteadOfInventingTimes() throws Exception {
        responseCode.set(404);
        var missing = http("GET", "/api/v1/job-sync/selectPlanByJobId?id=7", null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(missing.body()).get("code").stringValue()).isEqualTo("PLAN_NOT_FOUND");
        responseCode.set(503);
        assertThat(http("GET", "/api/v1/job-sync/selectPlanByJobId?id=7", null).statusCode()).isEqualTo(503);
        responseCode.set(200);
        var malformed = (tools.jackson.databind.node.ObjectNode) JSON.readTree(queryReply);
        malformed.putNull("nextFireTime"); // READY 必须有持久化的下一计划时间。
        queryReply = JSON.writeValueAsString(malformed);
        var response = http("GET", "/api/v1/job-sync/selectPlanByJobId?id=7", null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("code").stringValue()).isEqualTo("SCHEDULER_PROTOCOL_ERROR");
        assertThat(http("GET", "/api/v1/job-sync/selectPlanByJobId?id=0", null).statusCode()).isEqualTo(400);
    }

    private void makeDue() { jdbc.update("UPDATE job_sync_outbox SET next_attempt_at=?", Timestamp.from(Instant.now().minusSeconds(1))); }
    private Configuration config(String name, boolean enabled) {
        return new Configuration(name, null, "demoSuccess", "{\"source\":\"test\"}", ScheduleType.CRON,
                "0 */5 * * * *", "Asia/Shanghai", enabled, 30, 3, 5, MisfirePolicy.SKIP);
    }
    private HttpResponse<String> http(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/plans/selectByJobId", exchange -> {
                var body = queryReply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseCode.get(),body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.createContext("/internal/v1/plans/update", exchange -> {
                var input = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                received.add(input);
                if (entered != null) entered.countDown();
                try { if (release != null) release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                var body = JSON.writeValueAsString(Map.of("eventId", wrongAck ? UUID.randomUUID().toString() : input.get("eventId").stringValue(),
                        "jobId", input.get("jobId").longValue(), "configVersion", input.get("configVersion").longValue(),
                        "acceptedVersion", input.get("configVersion").longValue(), "result", "APPLIED")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseCode.get(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start(); return server;
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
