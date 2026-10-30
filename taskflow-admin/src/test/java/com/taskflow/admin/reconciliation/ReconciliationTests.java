package com.taskflow.admin.reconciliation;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.admin.job.model.JobDefinition.*;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.jobsync.service.IJobSyncService;
import com.taskflow.admin.reconciliation.client.PlanReplicaClient;
import com.taskflow.admin.reconciliation.mapper.ReconciliationMapper;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import com.taskflow.admin.reconciliation.service.IReconciliationService;
import com.taskflow.admin.support.TestTokens;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
@org.springframework.security.test.context.support.WithMockUser(username = "reconciler", authorities = {
        "job:read", "job:write", "job:delete", "job:operate", "job-sync:reconcile", "job-sync:repair"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class ReconciliationTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<Long, Replica> replicas = new ConcurrentHashMap<>();
    static final AtomicInteger queries = new AtomicInteger();
    static volatile int code = 200;
    static volatile String malformed;
    static volatile CountDownLatch entered, release;
    static final HttpServer scheduler = server();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("taskflow.job-sync.scheduler-url", () -> "http://127.0.0.1:" + scheduler.getAddress().getPort());
    }
    @Autowired IJobService jobs;
    @Autowired IJobSyncService sync;
    @Autowired IReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ReconciliationMapper mapper;
    @MockitoSpyBean PlanReplicaClient client;
    @Value("${local.server.port}") int port;

    @BeforeEach void clean() {
        for (String table : List.of("config_reconciliation_repair", "config_reconciliation_check", "config_reconciliation_scan", "job_sync_outbox", "job_definition"))
            jdbc.update("DELETE FROM " + table);
        gates(); replicas.clear(); queries.set(0); code = 200; malformed = null; entered = null; release = null;
    }
    @AfterAll static void stop() { scheduler.stop(0); }

    @Test void comparesCurrentReplicasDespiteHistoricalSuccessAndIncludesDeletionTombstones() {
        var missing = jobs.insert(config("lost"));
        var behind = jobs.insert(config("behind"));
        jobs.changeEnabled(behind.id(), 1, true);
        var deleted = jobs.insert(config("deleted"));
        jobs.delete(deleted.id(), 1);
        var matched = jobs.insert(config("matched"));
        var ahead = jobs.insert(config("ahead"));
        var conflict = jobs.insert(config("conflict"));
        var broken = jobs.insert(config("no-outbox"));
        sync.dispatchPending();
        assertThat(sync.selectByJobId(missing.id()).state()).isEqualTo("SYNCED");
        replicas.remove(missing.id());
        replicas.put(behind.id(), new Replica(behind.id(), 1, false, false));
        replicas.put(deleted.id(), new Replica(deleted.id(), 1, false, false));
        replicas.put(ahead.id(), new Replica(ahead.id(), 8, false, false));
        replicas.put(conflict.id(), new Replica(conflict.id(), 1, true, false));
        jdbc.update("DELETE FROM job_sync_outbox WHERE job_id=?", broken.id());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(client).select(anyList(), any());
        var scan = reconciliation.insert(UUID.randomUUID(), 0, 100);
        assertThat(scan.state()).isEqualTo("COMPLETED");
        assertThat(scan.actor()).isEqualTo("reconciler");
        assertThat(scan.checks()).extracting(Check::difference).containsExactly("MISSING", "BEHIND", "BEHIND", "MATCH", "AHEAD", "CONFLICT", "SOURCE_MISSING");
        var check = scan.checks().get(2);
        assertThat(check.source().deleted()).isTrue();
        assertThat(check.source().enabled()).isFalse();
        var repairId = UUID.randomUUID();
        var repair = reconciliation.repair(repairId, check.id());
        assertThat(repair.outcome()).isEqualTo("ENQUEUED");
        assertThat(reconciliation.repair(repairId, check.id())).isEqualTo(repair);
        assertThat(sync.selectByJobId(deleted.id()).eventId()).isEqualTo(check.source().eventId());
        assertThat(sync.selectByJobId(deleted.id()).syncedAt()).isNull();
        sync.dispatchPending();
        assertThat(replicas.get(deleted.id()).deleted()).isTrue();
        gates();
        assertThat(reconciliation.repair(UUID.randomUUID(), check.id()).outcome()).isEqualTo("ALREADY_REQUESTED");
        assertThat(sync.selectByJobId(deleted.id()).state()).isEqualTo("SYNCED");
        for (int index : List.of(3, 4, 5, 6)) {
            gates();
            assertThat(reconciliation.repair(UUID.randomUUID(), scan.checks().get(index).id()).outcome()).isEqualTo("NOT_REPAIRABLE");
        }
        assertThat(jobs.selectById(matched.id()).configVersion()).isEqualTo(1);
    }

    @Test void scanPaginationAndRequestIdentityAreDurableAndRateLimited() {
        var first = jobs.insert(config("one"));
        var second = jobs.insert(config("two"));
        var id = UUID.randomUUID();
        var scan = reconciliation.insert(id, 0, 1);
        assertThat(scan.hasMore()).isTrue();
        assertThat(scan.nextAfterJobId()).isEqualTo(first.id());
        assertThat(reconciliation.insert(id, 0, 1)).isEqualTo(scan);
        assertThat(queries).hasValue(1);
        assertThatThrownBy(() -> reconciliation.insert(id, 0, 2)).hasMessageContaining("请求标识");
        assertThatThrownBy(() -> reconciliation.insert(UUID.randomUUID(), scan.nextAfterJobId(), 1)).hasMessageContaining("限速");
        gates();
        var next = reconciliation.insert(UUID.randomUUID(), scan.nextAfterJobId(), 1);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.checks().getFirst().source().jobId()).isEqualTo(second.id());
        assertThat(reconciliation.selectById(id)).isEqualTo(scan);
    }

    @Test void repairRejectsStaleConfigurationAndDoesNotDisruptAnActiveDelivery() {
        var job = jobs.insert(config("stale"));
        var check = reconciliation.insert(UUID.randomUUID(), 0, 100).checks().getFirst();
        jobs.changeEnabled(job.id(), 1, true);
        assertThat(reconciliation.repair(UUID.randomUUID(), check.id()).outcome()).isEqualTo("STALE");
        gates();
        var latest = reconciliation.insert(UUID.randomUUID(), 0, 100).checks().getFirst();
        assertThat(reconciliation.repair(UUID.randomUUID(), latest.id()).outcome()).isEqualTo("ALREADY_PENDING");
        assertThat(sync.selectByJobId(job.id()).attempts()).isZero();
        assertThat(sync.selectByJobId(job.id()).configVersion()).isEqualTo(2);
    }

    @Test void repairAuditFailureRollsBackEnqueueAndExpiredChecksCannotRepair() {
        var job = jobs.insert(config("atomic"));
        sync.dispatchPending(); replicas.clear();
        var scan = reconciliation.insert(UUID.randomUUID(), 0, 100);
        var check = scan.checks().getFirst();
        doThrow(new IllegalStateException("audit unavailable")).when(mapper).insertRepair(any(), anyBoolean());
        assertThatThrownBy(() -> reconciliation.repair(UUID.randomUUID(), check.id())).hasMessageContaining("audit");
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("SYNCED");
        reset(mapper);
        var expired = new Check(check.id(), check.scanId(), check.source(), check.replica(), check.difference(), null, Instant.now().minusSeconds(301), null);
        jdbc.update("UPDATE config_reconciliation_check SET record_json=? WHERE check_id=?", JSON.writeValueAsString(expired), check.id().toString());
        assertThat(reconciliation.repair(UUID.randomUUID(), check.id()).outcome()).isEqualTo("EXPIRED");
        assertThat(sync.selectByJobId(job.id()).state()).isEqualTo("SYNCED");
    }

    @Test void repairNeverResetsAnOwnedSendingLease() {
        var job = jobs.insert(config("owned-send"));
        var check = reconciliation.insert(UUID.randomUUID(), 0, 100).checks().getFirst();
        UUID token = UUID.randomUUID();
        Instant until = Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE job_sync_outbox SET state='SENDING',attempts=2,lease_token=?,lease_until=? WHERE event_id=?",
                token.toString(), Timestamp.from(until), check.source().eventId().toString());
        assertThat(reconciliation.repair(UUID.randomUUID(), check.id()).outcome()).isEqualTo("ALREADY_PENDING");
        assertThat(sync.selectByJobId(job.id()).attempts()).isEqualTo(2);
        assertThat(sync.selectByJobId(job.id()).leaseUntil()).isEqualTo(until);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM job_sync_outbox WHERE event_id=?", String.class, check.source().eventId().toString())).isEqualTo(token.toString());
    }

    @Test void unavailableOrMalformedResponsesNeverBecomeMissingAndNeverEnqueue() {
        jobs.insert(config("query-failure"));
        for (String invalid : List.of("offline", "{}", "[{\"jobId\":999,\"configVersion\":1,\"deleted\":false,\"enabled\":false}]", "[{\"jobId\":1}]")) {
            gates();
            code = invalid.equals("offline") ? 503 : 200;
            malformed = invalid;
            var check = reconciliation.insert(UUID.randomUUID(), 0, 100).checks().getFirst();
            assertThat(check.difference()).isEqualTo("UNAVAILABLE");
            assertThat(check.errorCode()).isNotBlank();
            assertThat(reconciliation.repair(UUID.randomUUID(), check.id()).outcome()).isEqualTo("NOT_REPAIRABLE");
        }
    }

    @Test void competingScansAreBoundedAndSavingDoesNotWaitForRemoteQuery() throws Exception {
        var job = jobs.insert(config("slow-query"));
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var executor = new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            UUID id = UUID.randomUUID();
            var running = executor.submit(() -> reconciliation.insert(id, 0, 100));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(reconciliation.insert(id, 0, 100).state()).isEqualTo("RUNNING");
                assertThatThrownBy(() -> reconciliation.insert(UUID.randomUUID(), 0, 100)).hasMessageContaining("限速");
                assertThat(executor.submit(() -> jobs.changeEnabled(job.id(), 1, true)).get(2, TimeUnit.SECONDS).configVersion()).isEqualTo(2);
            } finally { release.countDown(); }
            assertThat(running.get(5, TimeUnit.SECONDS).state()).isEqualTo("COMPLETED");
        }
        assertThat(queries).hasValue(1);
    }

    @Test void expiredScanCannotPublishLateResultsOrReleaseNewScanGate() {
        jobs.insert(config("lease"));
        doAnswer(call -> {
            jdbc.update("UPDATE config_reconciliation_scan SET lease_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
            return call.callRealMethod();
        }).when(client).select(anyList(), any());
        UUID id = UUID.randomUUID();
        assertThat(reconciliation.insert(id, 0, 100).state()).isEqualTo("EXPIRED");
        assertThat(reconciliation.selectById(id).checks()).isEmpty();
        assertThat(reconciliation.insert(id, 0, 100).state()).isEqualTo("EXPIRED");
        assertThat(queries).hasValue(1);
    }

    @Test void concurrentRepairsRequeueOnceAndSameKeyWithDifferentCheckConflicts() throws Exception {
        jobs.insert(config("repair-race"));
        sync.dispatchPending(); replicas.clear();
        var check = reconciliation.insert(UUID.randomUUID(), 0, 100).checks().getFirst();
        UUID id = UUID.randomUUID();
        try (var executor = new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            var first = executor.submit(() -> reconciliation.repair(id, check.id()));
            var second = executor.submit(() -> reconciliation.repair(id, check.id()));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_reconciliation_repair", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> reconciliation.repair(id, UUID.randomUUID())).hasMessageContaining("请求标识");
        assertThatThrownBy(() -> reconciliation.repair(UUID.randomUUID(), check.id())).hasMessageContaining("限速");
    }

    @Test void realAdminHttpExposesPersistedScanAndRepairRecords() throws Exception {
        var job = jobs.insert(config("http"));
        sync.dispatchPending(); replicas.clear();
        String token = TestTokens.admin();
        assertThat(http("POST", "/api/v1/access/insert", null, token).statusCode()).isEqualTo(200);
        UUID requestId = UUID.randomUUID();
        var created = http("POST", "/api/v1/config-reconciliation/insert", Map.of("requestId", requestId, "afterJobId", 0, "batchSize", 100), token);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        assertThat(http("GET", "/api/v1/config-reconciliation/selectById?id=" + requestId, null, token).body()).contains("MISSING");
        String checkId = JSON.readTree(created.body()).path("data").path("checks").get(0).path("id").asString();
        UUID repairId = UUID.randomUUID();
        var repaired = http("POST", "/api/v1/config-reconciliation/updateRepair", Map.of("requestId", repairId, "checkId", checkId), token);
        assertThat(repaired.statusCode()).as(repaired.body()).isEqualTo(200);
        assertThat(repaired.body()).contains("ENQUEUED");
        assertThat(http("GET", "/api/v1/config-reconciliation/selectRepairById?id=" + repairId, null, token).body()).contains("ENQUEUED");
        sync.dispatchPending();
        assertThat(replicas.get(job.id()).configVersion()).isEqualTo(1);
    }

    private void gates() { jdbc.update("UPDATE config_reconciliation_gate SET next_allowed_at=?", Timestamp.from(Instant.now().minusSeconds(1))); }
    private Configuration config(String name) { return new Configuration(name, null, "demoSuccess", null, ScheduleType.MANUAL, null, "UTC", false, 30, 3, 5, MisfirePolicy.SKIP); }
    private HttpResponse<String> http(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/internal/v1/plans/selectVersions", exchange -> {
                queries.incrementAndGet();
                if (entered != null) {
                    entered.countDown();
                    try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                var ids = Arrays.stream(exchange.getRequestURI().getQuery().substring(4).split(",")).map(Long::valueOf).toList();
                var selected = ids.stream().map(replicas::get).filter(Objects::nonNull).toList();
                byte[] bytes = (malformed != null ? malformed : JSON.writeValueAsString(selected)).getBytes(StandardCharsets.UTF_8);
                int status = "test-control-token".equals(exchange.getRequestHeaders().getFirst("X-Taskflow-Token")) ? code : 401;
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.createContext("/internal/v1/plans/update", exchange -> {
                var event = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                long jobId = event.path("jobId").asLong(), version = event.path("configVersion").asLong();
                boolean deleted = event.path("deleted").asBoolean();
                replicas.compute(jobId, (key, before) -> before != null && before.configVersion() >= version ? before
                        : new Replica(jobId, version, deleted, !deleted && event.path("configuration").path("enabled").asBoolean()));
                byte[] bytes = JSON.writeValueAsBytes(Map.of("eventId", event.path("eventId").asString(), "jobId", jobId,
                        "configVersion", version, "acceptedVersion", replicas.get(jobId).configVersion(), "result", "APPLIED"));
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
