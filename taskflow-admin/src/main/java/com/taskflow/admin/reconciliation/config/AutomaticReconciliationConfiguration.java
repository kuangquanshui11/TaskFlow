package com.taskflow.admin.reconciliation.config;

import com.taskflow.admin.reconciliation.service.IAutomaticReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutomaticReconciliationProperties.class)
public class AutomaticReconciliationConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticReconciliationConfiguration.class);
    private final IAutomaticReconciliationService service;

    public AutomaticReconciliationConfiguration(IAutomaticReconciliationService service) { this.service = service; }

    @Bean
    public ThreadPoolTaskScheduler reconciliationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("config-reconciliation-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Scheduled(scheduler = "reconciliationScheduler", fixedDelayString = "${taskflow.reconciliation.automatic.tick-interval}",
            initialDelayString = "${taskflow.reconciliation.automatic.tick-interval}")
    public void scan() {
        try { service.scanNext(); }
        catch (RuntimeException failure) { LOG.warn("Automatic reconciliation scan failed: {}", failure.getClass().getSimpleName()); }
    }
}
