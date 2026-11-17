package com.taskflow.worker.execution.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution.attempt-logs")
public record AttemptLogProperties(boolean enabled, Path directory, int retentionDays) {
    public AttemptLogProperties {
        if (directory == null || retentionDays < 2 || retentionDays > 365)
            throw new IllegalArgumentException("Attempt log directory and retention of 2–365 days are required");
    }
}
