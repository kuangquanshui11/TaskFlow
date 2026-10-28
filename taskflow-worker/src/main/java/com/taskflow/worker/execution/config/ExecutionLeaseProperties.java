package com.taskflow.worker.execution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution.lease")
public record ExecutionLeaseProperties(Duration renewalInterval, Duration safetyMargin, Duration watchdogInterval) {
    public ExecutionLeaseProperties {
        if (renewalInterval == null || renewalInterval.compareTo(Duration.ofMillis(100)) < 0
                || renewalInterval.compareTo(Duration.ofMinutes(1)) > 0
                || safetyMargin == null || safetyMargin.compareTo(Duration.ofMillis(100)) < 0
                || safetyMargin.compareTo(Duration.ofSeconds(30)) > 0
                || watchdogInterval == null || watchdogInterval.compareTo(Duration.ofMillis(10)) < 0
                || watchdogInterval.compareTo(Duration.ofSeconds(1)) > 0)
            throw new IllegalArgumentException("Worker 续租参数不合法");
    }
}
