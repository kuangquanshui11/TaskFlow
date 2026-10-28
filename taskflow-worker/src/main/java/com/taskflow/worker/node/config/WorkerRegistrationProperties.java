package com.taskflow.worker.node.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.nodes")
public record WorkerRegistrationProperties(boolean enabled,String workerId,String displayName,URI schedulerUrl,
        String nodeToken,Duration connectTimeout,Duration requestTimeout,Duration heartbeatInterval) {
    public WorkerRegistrationProperties {
        if (workerId==null || !workerId.matches("[a-z0-9][a-z0-9_.-]{0,99}") || displayName==null || displayName.isBlank() || displayName.length()>100
                || schedulerUrl==null || !java.util.Set.of("http","https").contains(schedulerUrl.getScheme()) || schedulerUrl.getHost()==null
                || nodeToken==null || connectTimeout==null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout==null || requestTimeout.isNegative() || requestTimeout.isZero()
                || heartbeatInterval==null || heartbeatInterval.compareTo(Duration.ofMillis(200))<0 || heartbeatInterval.compareTo(Duration.ofMinutes(1))>0)
            throw new IllegalArgumentException("Worker 注册心跳配置不合法");
    }
}
