package com.taskflow.orders;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderHttpTests {
    @LocalServerPort int port;
    int post(String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json")
                .header("X-Request-Id","http-test").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) builder.header("X-Taskflow-Token",token);
        try(var http=HttpClient.newHttpClient()) {return http.send(builder.build(),HttpResponse.BodyHandlers.ofString()).statusCode();}
    }
    @Test void internalAndBusinessTokensAreIsolatedAndArgumentsValidated() throws Exception {
        assertThat(post("/internal/v1/orders/updateTimeout",null,"{\"batchSize\":1}")).isEqualTo(401);
        assertThat(post("/internal/v1/orders/updateTimeout","test-order-api","{\"batchSize\":1}")).isEqualTo(401);
        assertThat(post("/internal/v1/orders/updateTimeout","test-order-worker","{\"batchSize\":1}")).isEqualTo(200);
        assertThat(post("/internal/v1/orders/updateTimeout","test-order-worker","{\"batchSize\":0}")).isEqualTo(400);
        assertThat(post("/api/v1/orders/updatePayment","test-order-worker","{\"orderId\":\"absent\"}")).isEqualTo(401);
        assertThat(post("/api/v1/orders/insert","test-order-api","{\"requestKey\":\"bad\",\"skuId\":1,\"quantity\":0}")).isEqualTo(400);
    }
}
