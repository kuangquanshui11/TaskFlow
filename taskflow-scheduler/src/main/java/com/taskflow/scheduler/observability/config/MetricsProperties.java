package com.taskflow.scheduler.observability.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taskflow.metrics")
public record MetricsProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("15s") Duration sampleInterval) {
    public MetricsProperties {
        if (sampleInterval == null || sampleInterval.compareTo(Duration.ofSeconds(5)) < 0
                || sampleInterval.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("指标采样周期必须在 5s–5m 之间");
    }
}
