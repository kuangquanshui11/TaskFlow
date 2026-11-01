package com.taskflow.worker.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.order")
public record OrderHandlerProperties(boolean enabled,String token,Duration connectTimeout,Duration requestTimeout) { }
