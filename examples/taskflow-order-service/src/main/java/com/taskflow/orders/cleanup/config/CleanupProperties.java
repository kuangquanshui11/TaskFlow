package com.taskflow.orders.cleanup.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("order.cleanup")
public record CleanupProperties(boolean enabled, String token, boolean retentionEnabled,
                                @Min(1) @Max(500) int retentionBatchSize) { }
