package com.taskflow.scheduler.dispatch.config;

import com.taskflow.scheduler.dispatch.service.IDispatchRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taskflow.dispatch.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class DispatchRelayConfiguration {
    private final IDispatchRelayService relay;
    private final DispatchProperties properties;
    public DispatchRelayConfiguration(IDispatchRelayService relay, DispatchProperties properties) { this.relay = relay; this.properties = properties; }
    @Bean
    public ThreadPoolTaskScheduler dispatchRelayScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("dispatch-relay-"); return scheduler;
    }
    @Scheduled(scheduler = "dispatchRelayScheduler", fixedDelayString = "${taskflow.dispatch.scan-interval}", initialDelayString = "${taskflow.dispatch.scan-interval}")
    public void scan() { if (properties.relayEnabled()) relay.relay(); }
}
