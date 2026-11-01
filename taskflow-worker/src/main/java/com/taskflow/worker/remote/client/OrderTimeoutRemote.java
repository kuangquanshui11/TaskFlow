package com.taskflow.worker.remote.client;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="taskflow-order-service",contextId="orderTimeout",url="${taskflow.order.url}",configuration=OrderTimeoutRemote.Timeouts.class)
public interface OrderTimeoutRemote {
    record Batch(int batchSize,Instant cutoff,Instant afterExpiresAt,String afterId) { }
    record Result(int scanned,int closed,int skipped,int failed,Instant cutoff,Instant afterExpiresAt,String afterId) { }
    @PostMapping("/internal/v1/orders/updateTimeout") Result close(@RequestBody Batch batch,@RequestHeader Map<String,String> headers);
    class Timeouts {
        @org.springframework.context.annotation.Bean
        public com.taskflow.worker.order.client.OrderLoadBalancedTransport transport(OrderHandlerProperties properties,
                org.springframework.cloud.client.loadbalancer.LoadBalancerClient balancer,
                org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory factory,
                org.springframework.beans.factory.ObjectProvider<org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer> transformers) {
            return new com.taskflow.worker.order.client.OrderLoadBalancedTransport(
                    new com.taskflow.worker.order.client.OrderHttpTransport(properties.connectTimeout()),balancer,factory,transformers.orderedStream().toList());
        }
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options(OrderHandlerProperties properties) {
            return new feign.Request.Options(properties.connectTimeout().toMillis(),TimeUnit.MILLISECONDS,
                    properties.requestTimeout().toMillis(),TimeUnit.MILLISECONDS,false);
        }
    }
}
