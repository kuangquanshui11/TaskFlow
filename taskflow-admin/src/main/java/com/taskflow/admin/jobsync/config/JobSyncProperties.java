package com.taskflow.admin.jobsync.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.job-sync")
public record JobSyncProperties(boolean enabled, URI schedulerUrl, String controlToken,
        Duration connectTimeout, Duration requestTimeout, Duration scanInterval, int batchSize,
        int maxAttempts, Duration retryDelay, Duration maxRetryDelay, Duration claimLease) {
    public JobSyncProperties {
        if (schedulerUrl == null || !java.util.Set.of("http", "https").contains(schedulerUrl.getScheme())
                || controlToken == null || !positive(connectTimeout) || !positive(requestTimeout)
                || !positive(scanInterval) || scanInterval.toMillis() < 100
                || batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 20
                || !positive(retryDelay) || !positive(maxRetryDelay) || maxRetryDelay.compareTo(retryDelay) < 0
                || !positive(claimLease) || claimLease.compareTo(connectTimeout.plus(requestTimeout).plusSeconds(1)) <= 0) {
            throw new IllegalArgumentException("配置同步参数不合法，发送租约必须大于网络超时之和并留出余量");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
