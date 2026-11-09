package com.taskflow.orders.cleanup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.cleanup")
public record CleanupProperties(boolean enabled, String token) { }
