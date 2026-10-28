package com.taskflow.scheduler.remote.config;
import org.springframework.context.annotation.*;
import org.springframework.cloud.openfeign.*;
@Configuration(proxyBeanMethods=false)
@EnableFeignClients(basePackages="com.taskflow.scheduler.remote.client")
@org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient(name="taskflow-worker",configuration=WorkerLoadBalancerConfiguration.class)
public class RemoteCallConfiguration {
    @Bean public feign.Retryer noImplicitRemoteRetry() { return feign.Retryer.NEVER_RETRY; }
    @Bean public CircuitBreakerNameResolver circuitBreakerNames() { return (name,target,method)->name+"_"+method.getName(); }
}
