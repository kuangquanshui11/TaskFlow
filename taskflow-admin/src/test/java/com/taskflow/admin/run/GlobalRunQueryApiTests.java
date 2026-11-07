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
class GlobalRunQueryApiTests {
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean SchedulerClient scheduler;
    static final String PAGE = "{\"records\":[],\"total\":0,\"page\":1,\"pageSize\":20}";
    @Test void readerQueriesOutsideTransactionAndAllFiltersReachScheduler() throws Exception {
        when(scheduler.get(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return json.readTree(PAGE);
        });
        var id = UUID.randomUUID();
        var response = get("?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&jobId=7&state=FAILED&triggerType=CRON&runId=" + id, true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        verify(scheduler).get("/internal/v1/runs/selectGlobal?page=1&pageSize=20&from=2026-11-01T00%3A00%3A00Z&to=2026-11-02T00%3A00%3A00Z&jobId=7&state=FAILED&triggerType=CRON&runId=" + id);
        assertThat(get("?runId=" + id, true).statusCode()).isEqualTo(200);
        verify(scheduler).get("/internal/v1/runs/selectGlobal?page=1&pageSize=20&runId=" + id);
    }
    @Test void noPermissionIsForbiddenAndAnonymousIsUnauthenticated() throws Exception {
        assertThat(get("", false).statusCode()).isEqualTo(403);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/selectGlobal")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        verifyNoInteractions(scheduler);
    }
    @Test void rejectsBadArgumentsBeforeRemoteCall() throws Exception {
        for (String query : List.of("?jobId=-1", "?state=TIMED_OUT", "?triggerType=HTTP", "?runId=bad", "?page=10001", "?pageSize=101",
                "?from=2026-11-01T00:00:00Z", "?from=bad&to=bad", "?from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z",
                "?from=2026-11-01T00:00:00Z&to=2026-11-09T00:00:00Z",
                "?from=%2B500000000-01-01T00:00:00Z&to=%2B500000000-01-02T00:00:00Z"))
            assertThat(get(query, true).statusCode()).as(query).isEqualTo(400);
        verifyNoInteractions(scheduler);
    }
    @Test void remoteFailureRemainsUnavailable() throws Exception {
        when(scheduler.get(anyString())).thenThrow(new RunException(503, "SCHEDULER_UNAVAILABLE", "调度服务暂时不可用"));
        assertThat(get("", true).statusCode()).isEqualTo(503);
    }
    @Test void realClientAcceptsEmptyPageAndRejectsMalformedPages() {
        var remote = mock(com.taskflow.admin.remote.client.RunSchedulerRemote.class);
        var client = new SchedulerClient(new com.taskflow.admin.run.config.RunClientProperties(URI.create("http://scheduler.test"), "test",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(2)), json, remote);
        when(remote.get(eq("selectGlobal"), anyMap(), anyMap())).thenReturn(PAGE);
        assertThat(client.get("/internal/v1/runs/selectGlobal?page=1&pageSize=20").path("total").longValue()).isZero();
        for (String invalid : List.of("{}", "{\"records\":{},\"total\":0}",
                PAGE.replace("\"total\":0", "\"total\":-1"),
                PAGE.replace("\"total\":0", "\"total\":18446744073709551616"),
                PAGE.replace("\"page\":1", "\"page\":10001"),
                PAGE.replace("\"page\":1", "\"page\":4294967297"),
                PAGE.replace("\"page\":1", "\"page\":2"),
                PAGE.replace("\"pageSize\":20", "\"pageSize\":100"),
                "not json")) {
            when(remote.get(eq("selectGlobal"), anyMap(), anyMap())).thenReturn(invalid);
            assertThatThrownBy(() -> client.get("/internal/v1/runs/selectGlobal?page=1&pageSize=20")).isInstanceOfSatisfying(RunException.class,
                    e -> { assertThat(e.status()).isEqualTo(503); assertThat(e.code()).isEqualTo("SCHEDULER_PROTOCOL_ERROR"); });
        }
    }
    private HttpResponse<String> get(String query, boolean reader) throws Exception {
        String token = com.taskflow.admin.support.TestTokens.token(Map.of("resource_access", Map.of("taskflow-api", Map.of("roles", reader ? List.of("VIEWER") : List.of()))));
        com.taskflow.admin.support.TestSessionConfiguration.register(token);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/selectGlobal" + query)).header("Authorization", "Bearer " + token).GET().build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }
}
