package com.taskflow.worker.observability.config;

import java.time.Clock;
import com.taskflow.worker.observability.service.IMetricsSampler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsProperties.class)
@ConditionalOnProperty(name = "taskflow.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class MetricsConfiguration {
    private final IMetricsSampler sampler;
    public MetricsConfiguration(IMetricsSampler sampler) { this.sampler = sampler; }

    @Bean
    public static Clock metricsClock() { return Clock.systemUTC(); }

    @Bean
    public ThreadPoolTaskScheduler metricsSampleScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("metrics-sample-"); return scheduler;
    }

    @Scheduled(scheduler = "metricsSampleScheduler", fixedDelayString = "${taskflow.metrics.sample-interval}", initialDelayString = "${taskflow.metrics.sample-interval}")
    public void sample() { sampler.sample(); }
}
