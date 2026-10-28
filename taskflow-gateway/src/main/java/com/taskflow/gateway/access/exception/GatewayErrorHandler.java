package com.taskflow.gateway.access.exception;

import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {
    private final JsonMapper json;
    public GatewayErrorHandler(JsonMapper json) { this.json=json; }
    @Override
    public Mono<Void> handle(ServerWebExchange exchange,Throwable failure) {
        if (exchange.getResponse().isCommitted()) return Mono.error(failure);
        int status=failure instanceof ResponseStatusException response?response.getStatusCode().value():
                failure instanceof org.springframework.web.client.HttpClientErrorException client?client.getStatusCode().value():503;
        boolean rateUnavailable=failure instanceof ResponseStatusException response && "RATE_LIMIT_UNAVAILABLE".equals(response.getReason());
        var body=new LinkedHashMap<String,Object>();
        body.put("code",status==429?"RATE_LIMIT_EXCEEDED":rateUnavailable?"RATE_LIMIT_UNAVAILABLE":status==404?"ENDPOINT_NOT_FOUND":status==413?"REQUEST_TOO_LARGE":"GATEWAY_UNAVAILABLE");
        body.put("message",status==429?"请求过于频繁，请稍后重试":rateUnavailable?"限流服务暂时不可用，请稍后重试":status==404?"接口不存在":status==413?"请求体超过网关限制":"网关暂时无法连接管理服务");
        if(status==429) exchange.getResponse().getHeaders().set("Retry-After","1");
        body.put("data",null);body.put("requestId",exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(json.writeValueAsBytes(body))));
    }
}
