package com.taskflow.scheduler.plan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.plans")
public record PlanProperties(boolean initializationEnabled, Duration scanInterval, int batchSize) {
    public PlanProperties {
        if (scanInterval == null || scanInterval.toMillis() < 100 || batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("计划补算参数不合法");
    }
}
