package com.taskflow.gateway.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.rate-limit")
public record RateLimitProperties(String namespace, int readRate, int readBurst, int writeRate, int writeBurst,
                                  int executeRate, int executeBurst) {
    public RateLimitProperties {
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_-]{1,100}") ||
                readRate < 1 || readBurst < 1 || writeRate < 1 || writeBurst < 1 || executeRate < 1 || executeBurst < 1) {
            throw new IllegalArgumentException("Rate limit namespace and positive rates/capacities are required");
        }
    }
}
