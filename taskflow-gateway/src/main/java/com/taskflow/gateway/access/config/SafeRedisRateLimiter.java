package com.taskflow.gateway.access.config;

import java.time.Duration;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

public class SafeRedisRateLimiter extends RedisRateLimiter {
    private final String namespace;
    public SafeRedisRateLimiter(RateLimitProperties properties) {
        super(properties.readRate(), properties.readBurst());
        namespace = properties.namespace();
        getConfig().put(namespace + ":read", new Config().setReplenishRate(properties.readRate()).setBurstCapacity(properties.readBurst()));
        getConfig().put(namespace + ":write", new Config().setReplenishRate(properties.writeRate()).setBurstCapacity(properties.writeBurst()));
        getConfig().put(namespace + ":execute", new Config().setReplenishRate(properties.executeRate()).setBurstCapacity(properties.executeBurst()));
    }
    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        String group = id.substring(0, id.indexOf(':'));
        if (!java.util.Set.of("read", "write", "execute").contains(group)) return Mono.error(unavailable());
        // Gateway's RedisRateLimiter turns Redis errors into allowed=true/remaining=-1.
        // Reject that sentinel; otherwise a Redis outage silently removes the distributed limit.
        return super.isAllowed(namespace + ":" + group, id).timeout(Duration.ofSeconds(2))
                .flatMap(response -> "-1".equals(response.getHeaders().getOrDefault(getRemainingHeader(), "-1"))
                        ? Mono.error(unavailable()) : Mono.just(response))
                .onErrorMap(failure -> failure instanceof ResponseStatusException ? failure : unavailable());
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE");
    }
}
