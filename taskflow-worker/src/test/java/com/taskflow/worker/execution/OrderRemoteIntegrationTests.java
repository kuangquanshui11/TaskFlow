package com.taskflow.worker.execution;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.worker.execution.service.IHandlerRegistryService;
import com.taskflow.worker.remote.client.OrderTimeoutRemote;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test","test-order-remote"})
@ContextConfiguration(initializers=com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class OrderRemoteIntegrationTests {
    static HttpServer server;
    static AtomicReference<String> token=new AtomicReference<>();
    static {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/v1/orders/updateTimeout",exchange->{
                token.set(exchange.getRequestHeaders().getFirst("X-Taskflow-Token"));exchange.getRequestBody().readAllBytes();
                byte[] body="{\"scanned\":0,\"closed\":0,\"skipped\":0,\"failed\":0,\"cutoff\":\"2026-11-01T00:00:00Z\",\"afterExpiresAt\":null,\"afterId\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });server.start();
        } catch(java.io.IOException failure) {throw new ExceptionInInitializerError(failure);}
    }
    @DynamicPropertySource static void stub(DynamicPropertyRegistry registry) {registry.add("order.stub-port",()->server.getAddress().getPort());}
    @AfterAll static void stop() {server.stop(0);}
    @Autowired OrderTimeoutRemote remote;
    @Autowired IHandlerRegistryService handlers;
    @Test void discoversBusinessServiceAndRegistersEnabledHandler() {
        var result=remote.close(new OrderTimeoutRemote.Batch(2,null,null,null),Map.of("X-Taskflow-Token","test-order-worker","X-Request-Id","integration-run"));
        assertThat(result.scanned()).isZero();assertThat(token.get()).isEqualTo("test-order-worker");
        assertThat(handlers.keys()).contains("demoSuccess","orderTimeoutCheck");
    }
}
