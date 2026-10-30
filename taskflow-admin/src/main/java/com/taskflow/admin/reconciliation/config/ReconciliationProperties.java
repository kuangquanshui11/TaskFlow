package com.taskflow.admin.reconciliation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.reconciliation")
public record ReconciliationProperties(int maxBatchSize, Duration scanInterval, Duration repairInterval, Duration maxCheckAge) {
    public ReconciliationProperties {
        if (maxBatchSize < 1 || maxBatchSize > 100 || !valid(scanInterval) || !valid(repairInterval) || !valid(maxCheckAge))
            throw new IllegalArgumentException("对账批量应为 1..100，限速间隔与结果有效期至少为 1 秒");
    }
    private static boolean valid(Duration value) { return value != null && value.compareTo(Duration.ofSeconds(1)) >= 0; }
}
