package com.taskflow.gateway;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayTests {
    static final Queue<Map<String,String>> received=new ConcurrentLinkedQueue<>();
    static final AtomicInteger status=new AtomicInteger(200);
    static final HttpServer first=upstream("one");
    static final HttpServer second=upstream("two");
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.taskflow-admin[0].uri",()->"http://127.0.0.1:"+first.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.taskflow-admin[1].uri",()->"http://127.0.0.1:"+second.getAddress().getPort());
    }
    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired CircuitBreakerRegistry breakers;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.taskflow.gateway.access.config.SafeRedisRateLimiter limiter;
    @BeforeEach void reset() {
        org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(true,Map.of())))
                .when(limiter).isAllowed(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        status.set(200);received.clear();breakers.getAllCircuitBreakers().forEach(b->b.reset());
    }
    @AfterAll static void stop() { first.stop(0);second.stop(0); }
    @Test void publicApiPreservesPathBodyAndRequestIdAndLoadBalances() throws Exception {
        for (int i=0;i<4;i++) assertThat(call("GET","/api/v1/hello",null,Map.of("X-Request-Id","gateway-test")).statusCode()).isEqualTo(200);
        assertThat(received.stream().map(m->m.get("node"))).contains("one","two");
        var payload="{\"name\":\"网关任务\"}";
        assertThat(call("POST","/api/v1/jobs/insert",payload,Map.of("X-Request-Id","write-once")).statusCode()).isEqualTo(200);
        var request=received.stream().filter(m->m.get("method").equals("POST")).findFirst().orElseThrow();
        assertThat(request.get("path")).isEqualTo("/api/v1/jobs/insert");assertThat(request.get("body")).isEqualTo(payload);
        assertThat(request.get("requestId")).isEqualTo("write-once");
    }
    @Test void internalConfigDiscoveryAndActuatorPathsCannotReachUpstreams() throws Exception {
        for (var path:List.of("/internal/v1/runs/select","/eureka/apps","/taskflow-admin/cloud","/actuator/env","/actuator/gateway/routes",
                "/api/v1/%2e%2e/internal/v1/runs/select","/api/v1/jobs;ignored/select","/api/v1//jobs/select","/fallback/unavailable"))
            assertThat(call("GET",path,null,Map.of()).statusCode()).as(path).isEqualTo(404);
        assertThat(received).isEmpty();
        assertThat(call("GET","/actuator/health",null,Map.of()).statusCode()).isEqualTo(200);
    }
    @Test void browserCannotForwardAnInternalServiceToken() throws Exception {
        var response=call("GET","/api/v1/hello",null,Map.of("X-Taskflow-Token","spoofed-internal-token","X-Request-Id","bad id"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(received.peek().get("token")).isEmpty();
        assertThat(response.headers().firstValue("X-Request-Id").orElseThrow()).matches("[A-Za-z0-9._-]{1,64}");
    }
    @Test void writeFailureIsNotRetriedAndCircuitOpensThenRecovers() throws Exception {
        status.set(503);
        for (int i=0;i<2;i++) {
            var response=call("POST","/api/v1/jobs/insert","{}",Map.of());
            assertThat(response.statusCode()).isEqualTo(503);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("GATEWAY_UNAVAILABLE");
        }
        assertThat(received).hasSize(2);
        assertThat(call("POST","/api/v1/jobs/insert","{}",Map.of()).statusCode()).isEqualTo(503);
        assertThat(received).hasSize(2);
        status.set(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(call("GET","/api/v1/hello",null,Map.of()).statusCode()).isEqualTo(200));
    }
    @Test void oversizeBodyIsRejectedBeforeBusinessDispatch() throws Exception {
        assertThat(call("POST","/api/v1/jobs/insert","x".repeat(131073),Map.of()).statusCode()).isEqualTo(413);
        assertThat(received).isEmpty();
    }
    @Test void propagatesW3cTraceContext() throws Exception {
        String traceId="0123456789abcdef0123456789abcdef";
        var response=call("GET","/api/v1/hello",null,Map.of("traceparent","00-"+traceId+"-1111111111111111-01"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(received.peek().get("traceparent")).contains(traceId);
        assertThat(received.peek().get("traceparent").split("-")[2]).isNotEqualTo("1111111111111111");
    }
    @Test void invalidTokensAreRejectedBeforeRateLimitingOrDispatch() throws Exception {
        for (var token:List.of("invalid", com.taskflow.gateway.support.TestTokens.token(Map.of("aud",List.of("other-api"))),
                com.taskflow.gateway.support.TestTokens.token(Map.of("iss","https://other.test/realms/taskflow")),
                com.taskflow.gateway.support.TestTokens.token(Map.of("exp",Date.from(java.time.Instant.now().minusSeconds(180)))))) {
            var response=call("GET","/api/v1/jobs/select",null,Map.of("Authorization","Bearer "+token));
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHENTICATED", "requestId");
        }
        org.mockito.Mockito.verify(limiter,org.mockito.Mockito.never()).isAllowed(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThat(received).isEmpty();
    }
    @Test void rateLimitReturns429WithoutInvokingBusinessOrRetryingPost() throws Exception {
        org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(false,Map.of())))
                .when(limiter).isAllowed(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        var response=call("POST","/api/v1/runs/insert","{}",Map.of());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        assertThat(response.body()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(received).isEmpty();
    }
    private HttpResponse<String> call(String method,String path,String body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(!headers.containsKey("Authorization")) builder.header("Authorization", "Bearer " + com.taskflow.gateway.support.TestTokens.admin());
        headers.forEach(builder::header);
        if (body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        try (var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private static HttpServer upstream(String name) {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                received.add(Map.of("node",name,"path",exchange.getRequestURI().getPath(),"method",exchange.getRequestMethod(),
                        "body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),
                        "token",Objects.toString(exchange.getRequestHeaders().getFirst("X-Taskflow-Token"),""),
                        "requestId",Objects.toString(exchange.getRequestHeaders().getFirst("X-Request-Id"),""),
                        "traceparent",Objects.toString(exchange.getRequestHeaders().getFirst("traceparent"),"")));
                var body="{\"code\":\"OK\",\"data\":\""+name+"\"}";
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length());
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));exchange.close();
            });server.start();return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
