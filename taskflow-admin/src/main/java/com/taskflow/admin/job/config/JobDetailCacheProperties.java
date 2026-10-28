package com.taskflow.admin.job.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.jobs.detail-cache")
public record JobDetailCacheProperties(boolean enabled, Duration ttl, String keyPrefix, int evictionAttempts) {
    public JobDetailCacheProperties {
        if (ttl == null || ttl.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("任务详情缓存 ttl 必须至少为 1ms");
        }
        if (keyPrefix == null || !keyPrefix.startsWith("taskflow:admin:") || !keyPrefix.endsWith(":")) {
            throw new IllegalArgumentException("缓存前缀必须以 taskflow:admin: 开头并以冒号结尾");
        }
        if (evictionAttempts < 1 || evictionAttempts > 3) {
            throw new IllegalArgumentException("缓存删除总尝试次数必须为 1–3");
        }
    }
}
