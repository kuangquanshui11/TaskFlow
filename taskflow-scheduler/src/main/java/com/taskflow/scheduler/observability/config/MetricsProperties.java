package com.taskflow.scheduler.observability.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taskflow.metrics")
public record MetricsProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("15s") Duration sampleInterval, @DefaultValue List<String> expectedWorkers) {
    public MetricsProperties {
        if (sampleInterval == null || sampleInterval.compareTo(Duration.ofSeconds(5)) < 0
                || sampleInterval.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("指标采样周期必须在 5s–5m 之间");
        expectedWorkers = expectedWorkers.stream().distinct().toList();
        if (expectedWorkers.size() > 100 || expectedWorkers.stream().anyMatch(id -> !id.matches("[a-z0-9][a-z0-9_.-]{0,99}")))
            throw new IllegalArgumentException("监控节点最多 100 个，须使用有效的 Worker ID");
    }
}
