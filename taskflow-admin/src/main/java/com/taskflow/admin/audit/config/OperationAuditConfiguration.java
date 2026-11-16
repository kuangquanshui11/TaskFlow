package com.taskflow.admin.audit.config;

import com.taskflow.admin.audit.service.IOperationAuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taskflow.audit.scan-enabled", havingValue = "true", matchIfMissing = true)
public class OperationAuditConfiguration {
    private final IOperationAuditService audit;

    public OperationAuditConfiguration(IOperationAuditService audit) {
        this.audit = audit;
    }

    @Bean
    public ThreadPoolTaskScheduler operationAuditScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("operation-audit-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Scheduled(scheduler = "operationAuditScheduler", fixedDelayString = "${taskflow.audit.scan-interval:15s}",
            initialDelayString = "${taskflow.audit.scan-interval:15s}")
    public void reconcile() {
        audit.reconcile();
    }
}
