package com.taskflow.scheduler.plan.config;

import java.time.Clock;
import com.taskflow.scheduler.plan.service.IPlanService;
import com.taskflow.scheduler.plan.service.IPlanTriggerService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PlanProperties.class, PlanTriggerProperties.class})
public class PlanConfiguration {
    @Bean
    public Clock planClock() { return Clock.systemUTC(); }

    @Bean
    public ThreadPoolTaskScheduler planCalculationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("plan-calculation-");
        return scheduler;
    }

    @Bean
    public PendingPlanCalculation pendingPlanCalculation(IPlanService plans, PlanProperties properties) {
        return new PendingPlanCalculation(plans, properties);
    }

    @Bean
    public ThreadPoolTaskScheduler planTriggerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("plan-trigger-");
        return scheduler;
    }

    @Bean
    public DuePlanTrigger duePlanTrigger(IPlanTriggerService triggers, PlanTriggerProperties properties) {
        return new DuePlanTrigger(triggers, properties);
    }

    public static class DuePlanTrigger {
        private final IPlanTriggerService triggers;
        private final PlanTriggerProperties properties;
        public DuePlanTrigger(IPlanTriggerService triggers, PlanTriggerProperties properties) { this.triggers = triggers; this.properties = properties; }

        @Scheduled(scheduler = "planTriggerScheduler", fixedDelayString = "${taskflow.plans.trigger.scan-interval}",
                initialDelayString = "${taskflow.plans.trigger.scan-interval}")
        public void scan() {
            if (!properties.enabled()) return;
            try { triggers.scanDue(); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Due plan scan failed: {}", failure.getClass().getSimpleName());
            }
        }
    }

    public static class PendingPlanCalculation {
        private final IPlanService plans;
        private final PlanProperties properties;
        public PendingPlanCalculation(IPlanService plans, PlanProperties properties) { this.plans = plans; this.properties = properties; }

        @Scheduled(scheduler = "planCalculationScheduler", fixedDelayString = "${taskflow.plans.scan-interval}",
                initialDelayString = "${taskflow.plans.scan-interval}")
        public void initialize() {
            if (!properties.initializationEnabled()) return;
            try { plans.initializePending(); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Plan initialization failed: {}", error.getClass().getSimpleName());
            }
        }
    }
}
