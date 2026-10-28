package com.taskflow.scheduler.execution.config;

import com.taskflow.scheduler.execution.service.IRunService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RunProperties.class, ExecutionLeaseProperties.class})
@EnableScheduling
public class RunConfiguration {
    private final RunProperties properties;
    private final IRunService runs;
    public RunConfiguration(RunProperties properties, IRunService runs) { this.properties = properties; this.runs = runs; }
    @Bean
    public ThreadPoolTaskScheduler runDispatchScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("run-dispatch-");
        return scheduler;
    }
    @Scheduled(scheduler = "runDispatchScheduler", fixedDelayString = "${taskflow.execution.scan-interval}", initialDelayString = "${taskflow.execution.scan-interval}")
    public void scan() {
        if (properties.scanningEnabled()) {
            try { runs.dispatchPending(); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Run scan failed: {}", error.getClass().getSimpleName()); }
        }
    }
}
