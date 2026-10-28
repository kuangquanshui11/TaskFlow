package com.taskflow.scheduler.execution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution.lease")
public record ExecutionLeaseProperties(Duration duration) {
    public ExecutionLeaseProperties {
        if (duration == null || duration.compareTo(Duration.ofSeconds(10)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("执行租约时长必须在 10s–5m 之间");
    }
}
