package com.taskflow.worker.execution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution.result-persistence")
public record ResultPersistenceProperties(int maxAttempts, Duration retryDelay) {
    public ResultPersistenceProperties {
        if (maxAttempts < 1 || maxAttempts > 10 || retryDelay == null
                || retryDelay.compareTo(Duration.ofMillis(1)) < 0
                || retryDelay.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Worker 结果保存参数不合法");
        }
    }
}
