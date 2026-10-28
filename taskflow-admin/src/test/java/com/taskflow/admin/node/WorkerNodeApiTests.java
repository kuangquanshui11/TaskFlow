package com.taskflow.admin.node;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.admin.node.client.WorkerNodeClient;
import com.taskflow.admin.node.dto.WorkerNodeResponse.*;
import com.taskflow.admin.node.service.IWorkerNodeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.admin.support.IsolatedH2ContextInitializer.class)
class WorkerNodeApiTests {
    static final AtomicInteger remoteStatus=new AtomicInteger(200);
    static final AtomicInteger remoteCalls=new AtomicInteger();
    static volatile String reply;
    static volatile String token;
    static volatile String rawQuery;
    static final HttpServer scheduler=server();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("taskflow.nodes.scheduler-url",()->"http://127.0.0.1:"+scheduler.getAddress().getPort());
        registry.add("taskflow.nodes.control-token",()->"test-control-token");
    }
    @Autowired IWorkerNodeService nodes;
    @Autowired JsonMapper json;
    @MockitoSpyBean WorkerNodeClient client;
    @Value("${local.server.port}") int port;
    @BeforeEach void setup() {
        remoteStatus.set(200);remoteCalls.set(0);token=null;rawQuery=null;
        reply=json.writeValueAsString(new Page(List.of(node()),1,1,10,new Summary(1,1,0),Instant.parse("2026-10-27T00:00:00Z")));
    }
    @AfterAll static void close() { scheduler.stop(0); }
    @Test void realHttpProxyPreservesNodeDataAndEncodesFiltersWithoutLocalTransaction() throws Exception {
        doAnswer(call->{ assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod(); })
                .when(client).select(anyString(),anyInt(),anyInt());
        var response=get("select?keyword="+URLEncoder.encode("节点 & worker",StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.path("data").path("records").get(0).path("workerId").asString()).isEqualTo("worker-default");
        assertThat(body.path("data").path("records").get(0).has("sessionId")).isFalse();
        assertThat(token).isEqualTo("test-control-token");
        assertThat(URLDecoder.decode(rawQuery,StandardCharsets.UTF_8)).contains("keyword=节点 & worker");
    }
    @Test void invalidQueryReturns400WithoutCallingScheduler() throws Exception {
        assertThat(get("select?page=0").statusCode()).isEqualTo(400);
        assertThat(get("select?state=UNKNOWN").statusCode()).isEqualTo(400);
        assertThat(get("selectById?workerId=bad%2Fid").statusCode()).isEqualTo(400);
        assertThat(remoteCalls).hasValue(0);
    }
    @Test void missingNodeAndSchedulerOutageHaveDistinctResponses() throws Exception {
        remoteStatus.set(404);
        assertThat(get("selectById?workerId=missing").statusCode()).isEqualTo(404);
        remoteStatus.set(503);
        var response=get("select");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("SCHEDULER_UNAVAILABLE");
    }
    @Test void malformedAndInconsistentSchedulerDataDoesNotBecomeAnEmptyOrOnlineList() throws Exception {
        for (var value:List.of("null","{","{}",json.writeValueAsString(new Page(List.of(node()),1,1,10,new Summary(1,0,0),Instant.parse("2026-10-27T00:00:00Z"))))) {
            reply=value;
            var response=get("select");
            assertThat(response.statusCode()).as(value).isEqualTo(503);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("SCHEDULER_PROTOCOL_ERROR");
        }
    }
    @Test void detailReturnsRealRecordAndRejectsWrongIdentity() throws Exception {
        reply=json.writeValueAsString(node());
        assertThat(get("selectById?workerId=worker-default").statusCode()).isEqualTo(200);
        assertThat(get("selectById?workerId=other").statusCode()).isEqualTo(503);
    }
    private View node() {
        var now=Instant.parse("2026-10-27T00:00:00Z");
        return new View("worker-default","执行节点",1,List.of("demoSuccess"),2,4,1,1,"http",null,null,null,null,"ONLINE",true,true,now,now,now.plusSeconds(30));
    }
    private HttpResponse<String> get(String path) throws Exception {
        try(var http=HttpClient.newHttpClient()) { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/workers/"+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/v1/workers",exchange->{
                remoteCalls.incrementAndGet();token=exchange.getRequestHeaders().getFirst("X-Taskflow-Token");rawQuery=exchange.getRequestURI().getRawQuery();
                var bytes=reply.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(remoteStatus.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
