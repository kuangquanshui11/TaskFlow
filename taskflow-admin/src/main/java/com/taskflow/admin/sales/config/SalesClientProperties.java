package com.taskflow.admin.sales.config;
import java.time.Duration;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated
@ConfigurationProperties("taskflow.sales")
public record SalesClientProperties(String token,@NotNull Duration connectTimeout,@NotNull Duration requestTimeout) { }
