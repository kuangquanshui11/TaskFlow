package com.taskflow.worker.execution;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.worker.order.client.OrderHttpTransport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderHttpTransportTests {
    @Test void lostPostResponseIsNotSilentlyReplayed() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/close",exchange->{exchange.getRequestBody().readAllBytes();calls.incrementAndGet();exchange.close();});
        server.start();
        try(var transport=new OrderHttpTransport(Duration.ofSeconds(1))) {
            var request=feign.Request.create(feign.Request.HttpMethod.POST,"http://127.0.0.1:"+server.getAddress().getPort()+"/close",Map.of(),"{}".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8,null);
            assertThatThrownBy(()->transport.execute(request,new feign.Request.Options())).isInstanceOf(java.io.IOException.class);
            assertThat(calls).hasValue(1);
        } finally {server.stop(0);}
    }
    @Test void incompleteResponseBodyCannotOutliveRequestBudget() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/close",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,100);
            exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
        });
        server.start();
        try(var transport=new OrderHttpTransport(Duration.ofSeconds(1))) {
            var request=feign.Request.create(feign.Request.HttpMethod.POST,"http://127.0.0.1:"+server.getAddress().getPort()+"/close",Map.of(),"{}".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8,null);
            assertThatThrownBy(()->transport.execute(request,new feign.Request.Options(1000,200)))
                    .isInstanceOf(SocketTimeoutException.class);
        } finally {server.stop(0);}
    }
}
