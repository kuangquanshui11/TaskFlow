package com.taskflow.admin.reconciliation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.reconciliation.automatic")
public record AutomaticReconciliationProperties(boolean enabled, int batchSize, Duration tickInterval, Duration roundInterval,
                                                Duration retryInterval) {
    public AutomaticReconciliationProperties {
        if (batchSize < 1 || batchSize > 100 || tickInterval == null || tickInterval.compareTo(Duration.ofSeconds(1)) < 0
                || roundInterval == null || roundInterval.compareTo(tickInterval) < 0
                || retryInterval == null || retryInterval.compareTo(tickInterval) < 0)
            throw new IllegalArgumentException("自动对账批次间隔至少为 1 秒，轮次和重试间隔不得小于批次间隔");
    }
}
