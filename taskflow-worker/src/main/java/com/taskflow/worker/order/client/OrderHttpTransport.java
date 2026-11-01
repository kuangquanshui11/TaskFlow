package com.taskflow.worker.order.client;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import feign.*;

/** 避免 HttpURLConnection 在丢失响应时隐式重发 POST；业务重试由 Scheduler 持久化管理。 */
public final class OrderHttpTransport implements Client, AutoCloseable {
    private final HttpClient http;
    public OrderHttpTransport(Duration connectTimeout) {
        http=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Response execute(Request request,Request.Options options) throws IOException {
        var builder=HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofMillis(options.readTimeoutMillis()));
        var restricted=Set.of("connection","content-length","expect","host","upgrade");
        request.headers().forEach((name,values)->{if(!restricted.contains(name.toLowerCase(Locale.ROOT)))values.forEach(value->builder.header(name,value));});
        builder.method(request.httpMethod().name(),request.body()==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(request.body()));
        var pending=http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        try {
            // 完整响应体也受预算限制，不能收到响应头后无限等待消息体。
            var response=pending.get(options.readTimeoutMillis(),TimeUnit.MILLISECONDS);
            Map<String,Collection<String>> headers=new HashMap<>();response.headers().map().forEach(headers::put);
            return Response.builder().request(request).status(response.statusCode()).headers(headers)
                    .body(response.body()).build();
        } catch(TimeoutException timeout) {
            pending.cancel(true);throw new java.net.SocketTimeoutException("Order HTTP response timeout");
        } catch(ExecutionException failure) {
            if(failure.getCause() instanceof IOException io) throw io;
            throw new IOException("Order HTTP request failed",failure.getCause());
        } catch(InterruptedException interrupted) {
            pending.cancel(true);Thread.currentThread().interrupt();throw new InterruptedIOException("Order HTTP request interrupted");
        }
    }
    @Override public void close() { http.close(); }
}
