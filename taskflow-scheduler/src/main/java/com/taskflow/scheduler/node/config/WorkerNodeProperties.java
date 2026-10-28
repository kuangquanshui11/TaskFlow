package com.taskflow.scheduler.node.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.nodes")
public record WorkerNodeProperties(String nodeToken, String targetWorkerId, Duration offlineTimeout, boolean dispatchGatingEnabled) {
    public WorkerNodeProperties {
        if (nodeToken == null || targetWorkerId == null || !targetWorkerId.matches("[a-z0-9][a-z0-9_.-]{0,99}")
                || offlineTimeout == null || offlineTimeout.compareTo(Duration.ofSeconds(2)) < 0
                || offlineTimeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("节点管理配置不合法");
    }
}
