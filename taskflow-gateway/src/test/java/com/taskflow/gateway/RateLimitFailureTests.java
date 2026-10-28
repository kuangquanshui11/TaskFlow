package com.taskflow.gateway;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.taskflow.gateway.support.TestTokens;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password="})
@ActiveProfiles("test")
class RateLimitFailureTests {
    @Value("${local.server.port}") int port;
    @Test void redisOutageRejectsAuthenticatedCallsInsteadOfFailingOpen() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/jobs/select"))
                .header("Authorization", "Bearer " + TestTokens.admin()).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.body()).contains("RATE_LIMIT_UNAVAILABLE", "requestId");
        }
    }
}
