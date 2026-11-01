package com.taskflow.orders.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("order")
public record OrderProperties(String apiToken, String workerToken, @NotNull Duration paymentWindow,
        boolean cacheEnabled, @NotNull Duration cacheTtl, @NotNull Duration outboxRetryDelay,
        @Min(1) int outboxMaxAttempts) {
    public OrderProperties {
        if (paymentWindow == null || paymentWindow.isNegative() || paymentWindow.isZero()
                || cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()
                || outboxRetryDelay == null || outboxRetryDelay.isNegative() || outboxRetryDelay.isZero())
            throw new IllegalArgumentException("Order durations must be positive");
    }
}
