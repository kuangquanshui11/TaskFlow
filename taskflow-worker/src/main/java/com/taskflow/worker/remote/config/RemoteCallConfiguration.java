package com.taskflow.worker.remote.config;
import org.springframework.context.annotation.*;
import org.springframework.cloud.openfeign.*;
@Configuration(proxyBeanMethods=false)
@EnableFeignClients(basePackages="com.taskflow.worker.remote.client")
public class RemoteCallConfiguration {
    @Bean public feign.Retryer noImplicitRemoteRetry() { return feign.Retryer.NEVER_RETRY; }
    @Bean public CircuitBreakerNameResolver circuitBreakerNames() { return (name,target,method)->name+"_"+method.getName(); }
}
