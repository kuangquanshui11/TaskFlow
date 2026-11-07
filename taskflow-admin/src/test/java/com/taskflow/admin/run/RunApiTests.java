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
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean SchedulerClient scheduler;
    @BeforeEach void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:taskflow-admin-");
        }
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

    private JobDefinition job(String name) {
        return jobs.insert(new JobDefinition.Configuration(name, null, "demoSuccess", "{\"n\":1}",
                JobDefinition.ScheduleType.MANUAL, null, "Asia/Shanghai", false, 30, 3, 1, JobDefinition.MisfirePolicy.SKIP));
    }
    private HttpResponse<String> post(Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/insert")).header("Authorization", "Bearer " + com.taskflow.admin.support.TestTokens.admin())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }
}
