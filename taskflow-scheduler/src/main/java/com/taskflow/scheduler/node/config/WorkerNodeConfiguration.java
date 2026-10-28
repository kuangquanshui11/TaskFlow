package com.taskflow.scheduler.node.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(WorkerNodeProperties.class)
public class WorkerNodeConfiguration {
    @Bean public Clock nodeClock() { return Clock.systemUTC(); }
}
