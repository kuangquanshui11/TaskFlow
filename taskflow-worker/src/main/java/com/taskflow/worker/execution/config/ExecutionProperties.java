package com.taskflow.worker.execution.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution")
public record ExecutionProperties(URI schedulerUrl, String dispatchToken, String reportToken,
        Duration connectTimeout, Duration requestTimeout, int scanInterval, int parallelism,
        int maxPending, int maxReportAttempts, Duration reportRetryDelay, boolean scanningEnabled, Duration reportBudget) {
    public ExecutionProperties {
        if (schedulerUrl == null || !java.util.Set.of("http", "https").contains(schedulerUrl.getScheme())
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || parallelism < 1 || parallelism > 64 || maxPending < parallelism || maxPending > 10000
                || scanInterval < 100 || maxReportAttempts < 1 || maxReportAttempts > 1000
                || reportRetryDelay == null || reportRetryDelay.isNegative()
                || reportBudget == null || reportBudget.isNegative() || reportBudget.isZero()
                || reportBudget.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Worker 执行参数不合法");
        }
    }
}
