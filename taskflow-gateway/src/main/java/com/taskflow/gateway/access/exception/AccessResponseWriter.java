package com.taskflow.gateway.access.exception;

import java.util.LinkedHashMap;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AccessResponseWriter {
    private final JsonMapper json;
    public AccessResponseWriter(JsonMapper json) { this.json = json; }
    public Mono<Void> write(ServerWebExchange exchange, int status, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        if (status == 401) response.getHeaders().set("WWW-Authenticate", "Bearer");
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code); body.put("message", message); body.put("data", null);
        body.put("requestId", response.getHeaders().getFirst("X-Request-Id"));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.writeValueAsBytes(body))));
    }
}
