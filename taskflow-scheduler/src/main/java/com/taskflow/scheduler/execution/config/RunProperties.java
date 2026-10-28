package com.taskflow.scheduler.execution.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution")
public record RunProperties(URI workerUrl, String controlToken, String dispatchToken, String reportToken,
        Duration connectTimeout, Duration requestTimeout, int scanInterval, Duration dispatchRetryDelay,
        int maxDispatchAttempts, Duration leaseGrace, boolean scanningEnabled) {
    public RunProperties {
        if (workerUrl == null || !java.util.Set.of("http", "https").contains(workerUrl.getScheme())
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || dispatchRetryDelay == null || dispatchRetryDelay.isNegative()
                || leaseGrace == null || leaseGrace.isNegative() || scanInterval < 100
                || maxDispatchAttempts < 1 || maxDispatchAttempts > 20) {
            throw new IllegalArgumentException("Scheduler 执行参数不合法");
        }
    }
}
