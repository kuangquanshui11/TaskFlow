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
    @Test void reportGenerationRequiresInternalIdentityAndSamplesAreOffByDefault() throws Exception {
        String body="{\"requestKey\":\"report-http\",\"shopId\":8,\"timeZone\":\"Asia/Shanghai\"}";
        assertThat(post("/internal/v1/sales-reports/insert","test-order-api",body)).isEqualTo(401);
        assertThat(post("/internal/v1/sales-reports/insert",null,body)).isEqualTo(401);
        assertThat(post("/internal/v1/sales-reports/insert","test-order-worker",body)).isEqualTo(404);
        assertThat(post("/api/v1/sales-samples/insert","test-order-api","{}")).isEqualTo(404);
    }
    @Test void onlyBusinessIdentityCanPublishSalesInputsAndReviseAnExplicitDate() throws Exception {
        String shop="/internal/v1/sales-inputs/shops/insert";
        String shopBody="{\"shopId\":701,\"name\":\"ERP shop\"}";
        assertThat(post(shop,"test-order-worker",shopBody)).isEqualTo(401);
        assertThat(post(shop,"test-order-api",shopBody)).isEqualTo(200);
        assertThat(post(shop,"test-order-api",shopBody)).isEqualTo(200);
        String product="{\"shopId\":701,\"skuId\":701,\"name\":\"ERP SKU\",\"unitPriceCents\":200,"
                +"\"catalogSource\":\"erp\",\"catalogVersion\":1}";
        assertThat(post("/internal/v1/sales-inputs/products/update","test-order-api",product)).isEqualTo(200);
        String revise="/internal/v1/sales-reports/update";
        String scope=",\"shopId\":701,\"businessDate\":\"2026-10-01\",\"timeZone\":\"Asia/Shanghai\"";
        String update="{\"requestKey\":\"revise-http\""+scope+",\"reason\":\"Late data\"}";
        assertThat(post(revise,"test-order-worker",update)).isEqualTo(401);
        assertThat(post(revise,"test-order-api",update)).isEqualTo(404);
        assertThat(post("/internal/v1/sales-reports/insert","test-order-worker",
                "{\"requestKey\":\"initial-http\""+scope+"}")).isEqualTo(200);
        assertThat(post(revise,"test-order-api",update)).isEqualTo(200);
        assertThat(post(revise,"test-order-api",
                "{\"requestKey\":\"no-date\",\"shopId\":701,\"timeZone\":\"Asia/Shanghai\",\"reason\":\"Late data\"}")).isEqualTo(400);
    }
}
