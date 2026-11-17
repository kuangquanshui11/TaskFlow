package com.taskflow.admin.run;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.exception.RunException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class RunApiTests {
    @Autowired IJobService jobs;
    @Autowired com.taskflow.admin.audit.service.IOperationAuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean SchedulerClient scheduler;
    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-admin-");
        }
        jdbc.update("DELETE FROM operation_audit");
        jdbc.update("DELETE FROM job_definition");
    }
    @Test void newRunUsesAuthoritativeSnapshotOutsideTheDatabaseTransaction() throws Exception {
        var job = job("snapshot");
        var key = UUID.randomUUID();
        when(scheduler.get(anyString())).thenThrow(new RunException(404, "RUN_NOT_FOUND", "missing"));
        when(scheduler.post(anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<?,?> body = call.getArgument(1);
            var snapshot = json.valueToTree(body.get("snapshot"));
            assertThat(snapshot.get("jobId").longValue()).isEqualTo(job.id());
            assertThat(snapshot.get("configVersion").longValue()).isEqualTo(1);
            assertThat(snapshot.get("handlerKey").stringValue()).isEqualTo("demoSuccess");
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"state\":\"READY\"}");
        });
        var response = post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", key));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }
    @Test void retryOfAcceptedRequestSurvivesJobModificationAndDeletion() throws Exception {
        var job = job("old");
        var key = UUID.randomUUID(); var runId = UUID.randomUUID();
        jobs.delete(job.id(), 1);
        when(scheduler.get(anyString())).thenReturn(json.readTree("{\"id\":\"" + runId + "\",\"jobId\":" + job.id()
                + ",\"configVersion\":1,\"state\":\"SUCCEEDED\"}"));
        var response = post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", key));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readTree(response.body()).get("data").get("id").stringValue()).isEqualTo(runId.toString());
        verify(scheduler, never()).post(anyString(), any());
    }
    @Test void staleVersionAndDifferentKeyBindingReturnConflictWithoutDispatch() throws Exception {
        var job = job("version");
        when(scheduler.get(anyString())).thenThrow(new RunException(404, "RUN_NOT_FOUND", "missing"));
        assertThat(post(Map.of("jobId", job.id(), "configVersion", 2, "requestKey", UUID.randomUUID())).statusCode()).isEqualTo(409);
        doReturn(json.readTree("{\"id\":\"" + UUID.randomUUID()
                + "\",\"jobId\":" + (job.id()+1) + ",\"configVersion\":1,\"state\":\"READY\"}"))
                .when(scheduler).get(anyString());
        assertThat(post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", UUID.randomUUID())).statusCode()).isEqualTo(409);
        verify(scheduler, never()).post(anyString(), any());
    }
    @Test void schedulerOutageHasExplicitServiceUnavailableResponseAndDoesNotChangeJob() throws Exception {
        var job = job("offline");
        when(scheduler.get(anyString())).thenThrow(new RunException(503, "SCHEDULER_UNAVAILABLE", "调度服务暂时不可用"));
        var response = post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("SCHEDULER_UNAVAILABLE");
        assertThat(jobs.selectById(job.id()).configVersion()).isEqualTo(1);
    }
    @Test void rerunRequiresPermissionAndUsesTheAuthenticatedActorAndCurrentJobVersion() throws Exception {
        var job = job("current-rerun");
        var source = UUID.randomUUID();
        var key = UUID.randomUUID();
        var viewer = com.taskflow.admin.support.TestTokens.token(Map.of("resource_access",
                Map.of("taskflow-api", Map.of("roles", java.util.List.of("VIEWER")))));
        com.taskflow.admin.support.TestSessionConfiguration.register(viewer);
        var body = Map.of("sourceRunId", source, "configVersion", "1", "requestKey", key, "actor", "forged");
        String action = "/insertRerun";
        assertThat(post(action, body, viewer).statusCode()).isEqualTo(403);
        assertThat(get("/selectRerunConfiguration?sourceRunId=" + source, viewer).statusCode()).isEqualTo(403);
        verifyNoInteractions(scheduler);
        String admin = com.taskflow.admin.support.TestTokens.admin();
        when(scheduler.get("/internal/v1/runs/selectById?id=" + source)).thenReturn(json.createObjectNode()
                .put("id", source.toString()).put("jobId", job.id()).put("state", "FAILED").put("rerunEligible", true));
        when(scheduler.get("/internal/v1/runs/selectByRequestKey?requestKey=" + key))
                .thenThrow(new RunException(404, "RUN_NOT_FOUND", "missing"));
        var preview = get("/selectRerunConfiguration?sourceRunId=" + source, admin);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(json.readTree(preview.body()).path("data").path("configVersion").asString()).isEqualTo("1");
        var stale = Map.of("sourceRunId", source, "configVersion", 2, "requestKey", key);
        assertThat(post(action, stale, admin).statusCode()).isEqualTo(409);
        verify(scheduler, never()).post(anyString(), any());
        var accepted = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("state", "READY")
                .put("jobId", job.id()).put("sourceRunId", source.toString()).put("rerunActor", "test-admin").put("configVersion", 1);
        when(scheduler.post(eq("/internal/v1/runs/insertRerun"), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<?, ?> sent = call.getArgument(1);
            assertThat(sent.get("actor")).isEqualTo("test-admin");
            assertThat(sent.get("sourceRunId")).isEqualTo(source);
            assertThat(json.valueToTree(sent.get("snapshot")).path("configVersion").longValue()).isEqualTo(1);
            return accepted;
        });
        assertThat(post(action, body, admin).statusCode()).isEqualTo(202);
        jobs.delete(job.id(), 1);
        doReturn(accepted).when(scheduler).get("/internal/v1/runs/selectByRequestKey?requestKey=" + key);
        var retry = post(action, body, admin);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(json.readTree(retry.body()).path("data").path("id")).isEqualTo(accepted.path("id"));
        verify(scheduler, times(1)).post(eq("/internal/v1/runs/insertRerun"), any());
        assertThat(post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", key)).statusCode()).isEqualTo(409);
        var otherAdmin = com.taskflow.admin.support.TestTokens.token(Map.of("sub", "different-admin"));
        com.taskflow.admin.support.TestSessionConfiguration.register(otherAdmin);
        assertThat(post(action, body, otherAdmin).statusCode()).isEqualTo(409);
    }
    @Test void outcomeReviewRequiresAdminAndRecordsTheAuthenticatedActor() throws Exception {
        var viewer = com.taskflow.admin.support.TestTokens.token(Map.of("resource_access",
                Map.of("taskflow-api", Map.of("roles", java.util.List.of("VIEWER")))));
        com.taskflow.admin.support.TestSessionConfiguration.register(viewer);
        var key = UUID.randomUUID();
        var body = Map.of("runId", UUID.randomUUID(), "attemptId", UUID.randomUUID(), "requestKey", key,
                "decision", "NO_EFFECT", "evidence", "业务流水未入账", "actor", "forged");
        assertThat(post("/updateOutcomeReview", body, viewer).statusCode()).isEqualTo(403);
        verifyNoInteractions(scheduler);
        when(scheduler.post(eq("/internal/v1/runs/updateOutcomeReview"), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<?, ?> sent = call.getArgument(1);
            assertThat(sent.get("actor")).isEqualTo("test-admin");
            assertThat(sent.get("requestKey")).isEqualTo(key);
            return json.createObjectNode().put("requestKey", key.toString()).put("decision", "NO_EFFECT");
        });
        assertThat(post("/updateOutcomeReview", body, com.taskflow.admin.support.TestTokens.admin()).statusCode()).isEqualTo(200);
    }

    @Test void attemptTimelineRequiresRunReadAndProxiesOutsideTheLocalTransaction() throws Exception {
        var runId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var query = "runId=" + runId + "&attemptId=" + attemptId;
        var uri = URI.create("http://localhost:" + port + "/api/v1/runs/selectAttemptEvents?" + query);
        Map<String, Object> roles = Map.of("resource_access", Map.of("taskflow-api", Map.of("roles", java.util.List.of("VIEWER"))));
        var reader = com.taskflow.admin.support.TestTokens.token(roles);
        var noRead = com.taskflow.admin.support.TestTokens.token(
                Map.of("resource_access", Map.of("taskflow-api", Map.of("roles", java.util.List.of()))));
        com.taskflow.admin.support.TestSessionConfiguration.register(reader);
        com.taskflow.admin.support.TestSessionConfiguration.register(noRead);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var forbidden = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + noRead).GET().build();
            assertThat(client.send(forbidden, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            verifyNoInteractions(scheduler);
            when(scheduler.get("/internal/v1/runs/selectAttemptEvents?" + query)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return json.readTree("{\"workerStatus\":\"UNAVAILABLE\"}");
            });
            var allowed = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + reader).GET().build();
            var response = client.send(allowed, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("data").path("workerStatus").asString()).isEqualTo("UNAVAILABLE");
        }
    }

    @Test void timedOutTriggerReconcilesByReadAndKeepsTheAuthenticatedActor() throws Exception {
        var job = job("audit-run");
        var key = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var lookup = "/internal/v1/runs/selectByRequestKey?requestKey=" + key;
        when(scheduler.get(lookup)).thenThrow(new RunException(404, "RUN_NOT_FOUND", "missing"));
        when(scheduler.post(anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<?, ?> command = call.getArgument(1);
            assertThat(command.get("actor")).isEqualTo("test-admin");
            throw new RunException(503, "SCHEDULER_UNAVAILABLE", "response lost");
        });
        assertThat(post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", key, "actor", "forged")).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT status FROM operation_audit WHERE request_key=?", String.class, key.toString())).isEqualTo("PENDING");

        var evidence = json.createObjectNode().put("id", runId.toString()).put("state", "READY")
                .put("jobId", job.id()).put("configVersion", 1).put("triggerActor", "other-actor").put("requestKey", key.toString());
        doReturn(evidence).when(scheduler).get(lookup);
        jdbc.update("UPDATE operation_audit SET next_check_at=? WHERE request_key=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), key.toString());
        audit.reconcile();
        assertThat(jdbc.queryForObject("SELECT status FROM operation_audit WHERE request_key=?", String.class, key.toString())).isEqualTo("PENDING");
        evidence.put("triggerActor", "test-admin");
        jdbc.update("UPDATE operation_audit SET next_check_at=? WHERE request_key=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), key.toString());
        audit.reconcile();

        String administrator = com.taskflow.admin.support.TestTokens.admin();
        String viewer = com.taskflow.admin.support.TestTokens.token(Map.of("resource_access",
                Map.of("taskflow-api", Map.of("roles", java.util.List.of("VIEWER")))));
        com.taskflow.admin.support.TestSessionConfiguration.register(viewer);
        var auditUrl = "/api/v1/audits/select?requestKey=" + key;
        assertThat(call(auditUrl, viewer, null).statusCode()).isEqualTo(403);
        var response = call(auditUrl, administrator, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var records = json.readTree(response.body()).path("data").path("records");
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).path("runId").asString()).isEqualTo(runId.toString());
        assertThat(records.get(0).path("actor").asString()).isEqualTo("test-admin");
        assertThat(records.get(0).path("status").asString()).isEqualTo("APPLIED");
        var replay = post(Map.of("jobId", job.id(), "configVersion", 1, "requestKey", key));
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(json.readTree(replay.body()).path("data").has("triggerActor")).isFalse();
        verify(scheduler, times(1)).post(anyString(), any());
    }

    @Test void jobAuditsIncludeAllMutationsAndKeepRejectionsOutOfTheJobTransaction() throws Exception {
        var job = job("job-audit");
        job = jobs.update(job.id(), 1L, job.configuration());
        jobs.changeEnabled(job.id(), 2L, true);
        jobs.changeEnabled(job.id(), 3L, false);
        var response = call("/api/v1/jobs/delete", com.taskflow.admin.support.TestTokens.admin(),
                Map.of("id", job.id(), "configVersion", 3));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        jobs.delete(job.id(), 4L);

        assertThat(jdbc.queryForList("SELECT action FROM operation_audit WHERE job_id=? AND status='APPLIED'", String.class, job.id()))
                .containsExactlyInAnyOrder("JOB_INSERT", "JOB_UPDATE", "JOB_ENABLE", "JOB_DISABLE", "JOB_DELETE");
        assertThat(jdbc.queryForObject("SELECT result_code FROM operation_audit WHERE job_id=? AND status='REJECTED'", String.class, job.id()))
                .isEqualTo("JOB_VERSION_CONFLICT");
    }

    private HttpResponse<String> call(String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JobDefinition job(String name) {
        return jobs.insert(new JobDefinition.Configuration(name, null, "demoSuccess", "{\"n\":1}",
                JobDefinition.ScheduleType.MANUAL, null, "Asia/Shanghai", false, 30, 3, 1, JobDefinition.MisfirePolicy.SKIP));
    }
    private HttpResponse<String> post(Object body) throws Exception {
        return post("/insert", body, com.taskflow.admin.support.TestTokens.admin());
    }
    private HttpResponse<String> post(String action, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs" + action)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }
    private HttpResponse<String> get(String action, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs" + action))
                .header("Authorization", "Bearer " + token).GET().build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }
}
