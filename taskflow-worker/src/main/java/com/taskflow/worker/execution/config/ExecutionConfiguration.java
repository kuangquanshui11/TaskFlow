package com.taskflow.worker.execution.config;

import com.taskflow.worker.execution.service.IExecutionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ExecutionProperties.class, ResultPersistenceProperties.class, ExecutionLeaseProperties.class})
@EnableScheduling
public class ExecutionConfiguration {
    private final ExecutionProperties properties;
    private final IExecutionService executions;
    public ExecutionConfiguration(ExecutionProperties properties, IExecutionService executions) {
        this.properties = properties; this.executions = executions;
    }
    @Bean public ThreadPoolTaskScheduler workerReportScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("taskflow-report-"); return scheduler;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { executions.recover(); }
    @Scheduled(fixedDelayString = "${taskflow.execution.scan-interval}", initialDelayString = "${taskflow.execution.scan-interval}")
    public void scan() {
        if (properties.scanningEnabled()) {
            try { executions.executePending(); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Worker scan failed: {}", error.getClass().getSimpleName()); }
        }
    }
    @Scheduled(scheduler = "workerReportScheduler", fixedDelayString = "${taskflow.execution.scan-interval}", initialDelayString = "${taskflow.execution.scan-interval}")
    public void report() {
        if (properties.scanningEnabled()) {
            try { executions.reportPending(); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Worker report failed. type={}", error.getClass().getSimpleName()); }
        }
    }
}
