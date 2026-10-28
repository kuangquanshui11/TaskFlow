package com.taskflow.admin.remote;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.admin.node.dto.WorkerNodeResponse.View;
import com.taskflow.admin.node.exception.WorkerNodeException;
import com.taskflow.admin.node.service.IWorkerNodeService;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.cloud.openfeign.circuitbreaker.enabled=true","management.tracing.sampling.probability=1.0",
        "management.tracing.export.otlp.enabled=true","resilience4j.circuitbreaker.configs.default.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.configs.default.sliding-window-size=2","resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state=300ms"})
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
class RemoteGovernanceTests {
    static final AtomicInteger requests=new AtomicInteger();
    static final AtomicInteger status=new AtomicInteger(200);
    static final Queue<byte[]> exported=new ConcurrentLinkedQueue<>();
    static volatile String reply;
    static volatile String traceparent;
    static final HttpServer upstream=server();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("taskflow.nodes.scheduler-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());
        registry.add("taskflow.nodes.control-token",()->"isolated-control-token");
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1/traces");
    }
    @Autowired IWorkerNodeService nodes;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired Tracer tracer;
    @Autowired JsonMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @BeforeEach void reset() {
        requests.set(0);status.set(200);exported.clear();traceparent=null;breakers.getAllCircuitBreakers().forEach(b->b.reset());
        var now=Instant.now();
        reply=json.writeValueAsString(new View("worker-default","执行节点",1,List.of("demoSuccess"),2,4,0,0,"http",null,null,null,null,
                "ONLINE",true,true,now,now,now.plusSeconds(30)));
    }
    @AfterAll static void stop() { upstream.stop(0); }
    @Test void feignDoesNotReplayFailedCallAndCircuitRecovers() {
        status.set(503);
        for(int i=0;i<2;i++) assertThatThrownBy(()->nodes.selectById("worker-default")).isInstanceOf(WorkerNodeException.class);
        assertThat(requests).hasValue(2);
        assertThatThrownBy(()->nodes.selectById("worker-default")).isInstanceOf(WorkerNodeException.class);
        assertThat(requests).hasValue(2);
        status.set(200);
        await().ignoreExceptionsInstanceOf(WorkerNodeException.class).atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(nodes.selectById("worker-default").status()).isEqualTo("ONLINE"));
    }
    @Test void notFoundDoesNotTripAvailabilityCircuitOrGetRetried() {
        status.set(404);
        for(int i=0;i<5;i++) assertThatThrownBy(()->nodes.selectById("missing")).isInstanceOf(WorkerNodeException.class)
                .satisfies(error->assertThat(((WorkerNodeException)error).status()).isEqualTo(404));
        assertThat(requests).hasValue(5);
        assertThat(breakers.getAllCircuitBreakers()).allMatch(b->b.getState()==CircuitBreaker.State.CLOSED);
    }
    @Test void micrometerPropagatesFeignTraceAndActuallyExportsOtlp() throws Exception {
        var traceId=UUID.randomUUID().toString().replace("-","");
        var request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/workers/selectById?workerId=worker-default"))
                .header("traceparent","00-"+traceId+"-1111111111111111-01").GET().build();
        try(var http=java.net.http.HttpClient.newHttpClient()) {
            var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Trace-Id")).hasValue(traceId);
        }
        assertThat(traceparent).contains(traceId);
        assertThat(traceparent.split("-")[2]).isNotEqualTo("1111111111111111");
        await().atMost(Duration.ofSeconds(12)).untilAsserted(()->assertThat(exported).anyMatch(bytes->bytes.length>0));
    }
    private static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/v1/workers",exchange->{
                requests.incrementAndGet();traceparent=exchange.getRequestHeaders().getFirst("traceparent");
                byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.createContext("/v1/traces",exchange->{
                exported.add(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(200,-1);exchange.close();
            });server.start();return server;
        } catch(java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
