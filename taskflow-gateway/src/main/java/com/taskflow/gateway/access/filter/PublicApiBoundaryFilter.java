package com.taskflow.gateway.access.filter;

import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-100)
public class PublicApiBoundaryFilter implements WebFilter {
    private final io.micrometer.tracing.Tracer tracer;
    public PublicApiBoundaryFilter(io.micrometer.tracing.Tracer tracer) { this.tracer=tracer; }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        var raw=exchange.getRequest().getURI().getRawPath(); var lower=raw.toLowerCase(Locale.ROOT);
        boolean ambiguous=lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25")
                || raw.contains("..") || raw.contains("//") || raw.contains("\\") || raw.contains(";");
        boolean health=raw.equals("/actuator/health") || raw.equals("/actuator/health/readiness") || raw.equals("/actuator/health/liveness");
        if (ambiguous || !(raw.startsWith("/api/v1/") || health)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);return exchange.getResponse().setComplete();
        }
        var supplied=exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        var requestId=supplied!=null && supplied.matches("[A-Za-z0-9._-]{1,64}")?supplied:UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id",requestId);
        var span=tracer.currentSpan();
        if(span!=null) exchange.getResponse().getHeaders().set("X-Trace-Id",span.context().traceId());
        exchange.getResponse().getHeaders().set("Cache-Control","no-store");
        var request=exchange.getRequest().mutate().headers(headers->{
            headers.remove("X-Taskflow-Token");
            headers.remove("Forwarded");headers.remove("X-Forwarded-Host");headers.remove("X-Forwarded-Prefix");
            headers.set("X-Request-Id",requestId);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
