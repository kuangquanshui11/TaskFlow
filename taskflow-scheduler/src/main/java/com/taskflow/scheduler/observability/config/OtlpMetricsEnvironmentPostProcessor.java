package com.taskflow.scheduler.observability.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 在 YAML 载入后推导缺省指标地址；显式 url 配置始终优先。 */
public class OtlpMetricsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String traces = environment.getProperty("TASKFLOW_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
        environment.getPropertySources().addLast(new MapPropertySource("taskflowOtlpMetricsDefaults",
                Map.of("management.otlp.metrics.export.url", traces.replace("/v1/traces", "/v1/metrics"))));
    }
    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
}
