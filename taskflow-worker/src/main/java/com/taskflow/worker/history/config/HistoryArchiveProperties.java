package com.taskflow.worker.history.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.history-archive")
public record HistoryArchiveProperties(Mode mode, int retentionDays, int batchSize, Duration interval) {
    public enum Mode { OFF, PREVIEW, ONCE, SCHEDULED }

    public HistoryArchiveProperties {
        if (mode == null || retentionDays < 7 || retentionDays > 3650 || batchSize < 1 || batchSize > 100
                || interval == null || interval.compareTo(Duration.ofSeconds(30)) < 0)
            throw new IllegalArgumentException("Invalid taskflow.history-archive configuration");
    }
}
