package com.taskflow.gateway.access.config;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
    @Bean
    SafeRedisRateLimiter safeRedisRateLimiter(RateLimitProperties properties) {
        return new SafeRedisRateLimiter(properties);
    }
    @Bean
    KeyResolver accessRateLimitKeyResolver() {
        return exchange -> {
            String path = exchange.getRequest().getPath().value();
            String group = "GET".equals(exchange.getRequest().getMethod().name()) || path.equals("/api/v1/jobs/preview")
                    ? "read" : path.equals("/api/v1/runs/insert") || path.equals("/api/v1/job-sync/updateRetry") ? "execute" : "write";
            return exchange.getPrincipal().cast(JwtAuthenticationToken.class)
                    .map(auth -> group + ":" + digest(auth.getToken().getIssuer() + "\0" + auth.getToken().getSubject()))
                    // Only the public login configuration can reach this point anonymously.
                    .switchIfEmpty(Mono.fromSupplier(() -> "read:anonymous:" + digest(exchange.getRequest().getRemoteAddress() == null
                            ? "unknown" : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress())));
        };
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
