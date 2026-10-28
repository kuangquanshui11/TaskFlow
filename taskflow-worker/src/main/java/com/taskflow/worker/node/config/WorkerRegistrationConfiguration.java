package com.taskflow.worker.node.config;

import com.taskflow.worker.node.service.IWorkerRegistrationService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(WorkerRegistrationProperties.class)
public class WorkerRegistrationConfiguration {
    private final WorkerRegistrationProperties properties;
    private final IWorkerRegistrationService registration;
    public WorkerRegistrationConfiguration(WorkerRegistrationProperties properties,IWorkerRegistrationService registration) { this.properties=properties;this.registration=registration; }
    @Bean public ThreadPoolTaskScheduler workerHeartbeatScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("worker-heartbeat-");return scheduler;
    }
    @Scheduled(scheduler="workerHeartbeatScheduler",fixedDelayString="${taskflow.nodes.heartbeat-interval}",initialDelayString="${taskflow.nodes.initial-delay}")
    public void pulse() {
        if (!properties.enabled()) return;
        try { registration.pulse(); }
        catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Heartbeat cycle failed. type={}",failure.getClass().getSimpleName()); }
    }
}
