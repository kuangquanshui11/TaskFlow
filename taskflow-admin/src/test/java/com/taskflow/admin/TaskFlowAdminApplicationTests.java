package com.taskflow.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class TaskFlowAdminApplicationTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthEndpointIsAvailable() throws Exception {
        var response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void existingHelloEndpointIsPreserved() throws Exception {
        var response = get("/api/v1/hello");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("TaskFlow 已启动");
    }

    @Test
    void legacyConsoleCannotBypassTheAuthenticatedNginxConsole() throws Exception {
        var response = get("/");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + com.taskflow.admin.support.TestTokens.admin())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
