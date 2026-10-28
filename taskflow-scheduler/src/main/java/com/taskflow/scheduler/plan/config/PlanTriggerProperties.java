package com.taskflow.scheduler.plan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.plans.trigger")
public record PlanTriggerProperties(boolean enabled, Duration scanInterval, int batchSize, Duration misfireGrace) {
    public PlanTriggerProperties {
        if (scanInterval == null || scanInterval.toMillis() < 100 || batchSize < 1 || batchSize > 1000
                || misfireGrace == null || misfireGrace.isNegative())
            throw new IllegalArgumentException("到期扫描参数不合法");
    }
}
