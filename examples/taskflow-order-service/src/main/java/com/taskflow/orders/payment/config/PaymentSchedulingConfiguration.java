package com.taskflow.orders.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class PaymentSchedulingConfiguration {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler orderPaymentScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("order-payment-");
        return scheduler;
    }
}
