package com.taskflow.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.operations")
public record OrderOperationsProperties(boolean enabled, String token) { }
