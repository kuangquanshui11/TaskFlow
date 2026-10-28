package com.taskflow.admin.node.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.nodes")
public record WorkerNodeClientProperties(URI schedulerUrl,String controlToken,Duration connectTimeout,Duration requestTimeout) {
    public WorkerNodeClientProperties {
        if (schedulerUrl==null || !java.util.Set.of("http","https").contains(schedulerUrl.getScheme()) || schedulerUrl.getHost()==null || controlToken==null
                || connectTimeout==null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout==null || requestTimeout.isNegative() || requestTimeout.isZero()) throw new IllegalArgumentException("节点查询配置不合法");
    }
}
