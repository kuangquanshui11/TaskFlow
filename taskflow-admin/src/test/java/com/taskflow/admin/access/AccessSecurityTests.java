package com.taskflow.admin.access;

import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class AccessSecurityTests {
    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired IJobService jobs;

    @Test void anonymousCallsAndDirectAdminAccessRequireLogin() throws Exception {
        for (String path : List.of("/api/v1/jobs/selectSummary", "/api/v1/access/selectCurrent", "/api/v1/hello")) {
            var response = call("GET", path, null, null);
            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            assertThat(json.readTree(response.body()).path("requestId").asString()).isNotBlank();
        }
        var configuration = call("GET", "/api/v1/access/selectConfiguration", null, null);
        assertThat(configuration.statusCode()).isEqualTo(200);
        assertThat(configuration.body()).contains("taskflow-console").doesNotContain("private", "password", "secret");
        assertThat(call("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
    }
    @Test void invalidExpiredFutureWrongIssuerAndAudienceTokensAreRejected() throws Exception {
        var valid = TestTokens.admin();
        var expired = Date.from(Instant.now().minusSeconds(180));
        var future = Date.from(Instant.now().plusSeconds(180));
        for (String token : List.of("invalid", valid.substring(0, valid.lastIndexOf('.') + 1) + "AAAA",
                TestTokens.token(Map.of("exp", expired)), TestTokens.token(Map.of("nbf", future)),
                TestTokens.token(Map.of("iss", "https://other.test/realms/taskflow")),
                TestTokens.token(Map.of("aud", List.of("other-api"))), TestTokens.token(Collections.singletonMap("exp", null)),
                TestTokens.token(Map.of("sub", "")),
                "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.")) {
            assertThat(call("GET", "/api/v1/jobs/selectSummary", token, null).statusCode()).isEqualTo(401);
        }
    }
    @Test void viewerCanReadAndPreviewButCannotMutateOrTriggerRuns() throws Exception {
        String token = role("VIEWER");
        assertThat(call("GET", "/api/v1/jobs/selectSummary", token, null).statusCode()).isEqualTo(200);
        var preview = call("POST", "/api/v1/jobs/preview", token,
                "{\"cronExpression\":\"0 * * * * *\",\"timeZone\":\"Asia/Shanghai\",\"count\":3}");
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        for (String path : List.of("/api/v1/jobs/insert", "/api/v1/jobs/update", "/api/v1/jobs/delete",
                "/api/v1/jobs/enable", "/api/v1/jobs/disable", "/api/v1/runs/insert", "/api/v1/job-sync/updateRetry")) {
            assertThat(call("POST", path, token, "{}").statusCode()).as(path).isEqualTo(403);
        }
    }
    @Test void operatorCannotEditDefinitionsAndForeignRolesAndScopesGrantNothing() throws Exception {
        assertThat(call("POST", "/api/v1/jobs/insert", role("OPERATOR"), "{}").statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/v1/jobs/enable", role("OPERATOR"), "{}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/runs/insert", role("OPERATOR"), "{}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/jobs/insert", role("ADMIN"), "{}").statusCode()).isEqualTo(400);
        String foreign = TestTokens.token(Map.of("resource_access", Map.of("other", Map.of("roles", List.of("ADMIN"))),
                "scope", "job:write job:read", "realm_access", Map.of("roles", List.of("ADMIN"))));
        assertThat(call("POST", "/api/v1/access/insert", foreign, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/jobs/selectSummary", foreign, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/unconfigured", role("ADMIN"), null).statusCode()).isEqualTo(403);
        var current = call("GET", "/api/v1/access/selectCurrent", role("OPERATOR"), null);
        assertThat(current.body()).contains("run:trigger", "job:operate").doesNotContain("job:write", "job:delete");
    }
    @Test
    @org.springframework.security.test.context.support.WithMockUser(authorities = "job:read")
    void serviceMutationCannotBypassPermissions() {
        assertThatThrownBy(() -> jobs.insert(null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void jwtAloneCannotBypassRedisSessionAndLogoutCannotBeUndoneByReplayingIt() throws Exception {
        String token = TestTokens.token(Map.of());
        assertThat(call("GET", "/api/v1/jobs/selectSummary", token, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/access/insert", token, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/jobs/selectSummary", token, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/access/disable", token, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/jobs/selectSummary", token, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/access/insert", token, null).statusCode()).isEqualTo(401);
        String oldLogin = TestTokens.token(Map.of("auth_time", Instant.now().minusSeconds(1800).getEpochSecond()));
        assertThat(call("POST", "/api/v1/access/insert", oldLogin, null).statusCode()).isEqualTo(401);
    }
    private String role(String role) throws Exception {
        String token = TestTokens.token(Map.of("resource_access", Map.of("taskflow-api", Map.of("roles", List.of(role)))));
        assertThat(call("POST", "/api/v1/access/insert", token, null).statusCode()).isEqualTo(200);
        return token;
    }
    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
