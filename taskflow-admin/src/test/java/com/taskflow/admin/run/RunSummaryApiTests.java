package com.taskflow.admin.run;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.exception.RunException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class RunSummaryApiTests {
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean SchedulerClient scheduler;
    static final String SUMMARY = """
            {"from":"2026-10-30T00:00:00Z","to":"2026-10-31T00:00:00Z","observedAt":"2026-10-31T00:00:00Z",
            "total":0,"stateCounts":{"READY":0,"RUNNING":0,"RETRY_WAIT":0,"SUCCEEDED":0,"FAILED":0},
            "finished":0,"succeeded":0,"failed":0,"successRate":null,"attemptTotal":0,"retriedRunCount":0,
            "latency":{"avgMillis":null,"p95Millis":null,"sampled":0,"truncated":false},"failures":[]}
            """;
    @Test void readerReceivesCompleteSummaryOutsideSqlTransaction() throws Exception {
        when(scheduler.get(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return json.readTree(SUMMARY);
        });
        var response = get("", true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data")).isEqualTo(json.readTree(SUMMARY));
        verify(scheduler).get("/internal/v1/runs/selectSummary");
        assertThat(get("?from=2026-10-24T00:00:00Z&to=2026-10-31T00:00:00Z", true).statusCode()).isEqualTo(200);
        verify(scheduler).get("/internal/v1/runs/selectSummary?from=2026-10-24T00%3A00%3A00Z&to=2026-10-31T00%3A00%3A00Z");
    }
    @Test void authenticatedWithoutRunReadIsForbidden() throws Exception {
        assertThat(get("", false).statusCode()).isEqualTo(403); verifyNoInteractions(scheduler);
    }
    @Test void rejectsInvalidWindowBeforeCallingScheduler() throws Exception {
        for (String query : List.of("?from=2026-10-30T00:00:00Z", "?to=2026-10-31T00:00:00Z", "?from=bad&to=bad",
                "?from=2026-10-31T00:00:00Z&to=2026-10-30T00:00:00Z", "?from=2026-10-31T00:00:00Z&to=2026-10-31T00:00:00Z",
                "?from=2026-10-23T23:59:59Z&to=2026-10-31T00:00:00Z", "?from=2026-10-30T00:00:00&to=2026-10-31T00:00:00Z",
                "?from=9999-12-31T00:00:00Z&to=9999-12-31T23:59:59.999000001Z")) {
            var response = get(query, true); assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").stringValue()).isEqualTo("INVALID_ARGUMENT");
        }
        verifyNoInteractions(scheduler);
    }
    @Test void schedulerUnavailableStaysServiceUnavailable() throws Exception {
        when(scheduler.get(anyString())).thenThrow(new RunException(503, "SCHEDULER_UNAVAILABLE", "调度服务暂时不可用"));
        var response = get("", true); assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).path("code").stringValue()).isEqualTo("SCHEDULER_UNAVAILABLE");
    }
    @Test void missingSummaryFieldsAreProtocolErrors() throws Exception {
        for (String field : List.of("total", "stateCounts", "latency", "failures")) {
            var data = (tools.jackson.databind.node.ObjectNode) json.readTree(SUMMARY); data.remove(field);
            when(scheduler.get(anyString())).thenReturn(data);
            var response = get("", true); assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).path("code").stringValue()).isEqualTo("SCHEDULER_PROTOCOL_ERROR");
        }
    }
    @Test void realClientExplicitlyAcceptsSummaryInsteadOfRequiringIdAndState() {
        var remote = mock(com.taskflow.admin.remote.client.RunSchedulerRemote.class);
        when(remote.get(eq("selectSummary"), anyMap(), anyMap())).thenReturn(SUMMARY);
        var client = new SchedulerClient(new com.taskflow.admin.run.config.RunClientProperties(URI.create("http://scheduler.test"), "test",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(2)), json, remote);
        assertThat(client.get("/internal/v1/runs/selectSummary")).isEqualTo(json.readTree(SUMMARY));
        assertThat(client.get("/internal/v1/runs/selectSummary?from=2026-10-30T00%3A00%3A00Z&to=2026-10-31T00%3A00%3A00Z")).isEqualTo(json.readTree(SUMMARY));
        when(remote.get(eq("selectSummary"), anyMap(), anyMap())).thenReturn("{\"total\":0}");
        assertThatThrownBy(() -> client.get("/internal/v1/runs/selectSummary")).isInstanceOfSatisfying(RunException.class,
                error -> { assertThat(error.status()).isEqualTo(503); assertThat(error.code()).isEqualTo("SCHEDULER_PROTOCOL_ERROR"); });
    }
    private HttpResponse<String> get(String query, boolean reader) throws Exception {
        String token = com.taskflow.admin.support.TestTokens.token(Map.of("resource_access", Map.of("taskflow-api", Map.of("roles", reader ? List.of("VIEWER") : List.of()))));
        com.taskflow.admin.support.TestSessionConfiguration.register(token);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/selectSummary" + query))
                .header("Authorization", "Bearer " + token).GET().build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }
}
