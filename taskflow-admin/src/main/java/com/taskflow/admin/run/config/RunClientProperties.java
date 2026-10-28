package com.taskflow.admin.run.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.execution")
public record RunClientProperties(URI schedulerUrl, String controlToken, Duration connectTimeout, Duration requestTimeout) {
    public RunClientProperties {
        if (schedulerUrl == null || !java.util.Set.of("http", "https").contains(schedulerUrl.getScheme())
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("Admin 执行客户端配置不合法");
    }
}
