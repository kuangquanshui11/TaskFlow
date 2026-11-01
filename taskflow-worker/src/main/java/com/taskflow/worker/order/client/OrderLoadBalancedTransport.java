package com.taskflow.worker.order.client;

import java.util.List;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.*;

public final class OrderLoadBalancedTransport extends FeignBlockingLoadBalancerClient implements AutoCloseable {
    private final OrderHttpTransport delegate;
    public OrderLoadBalancedTransport(OrderHttpTransport delegate,LoadBalancerClient balancer,
            LoadBalancerClientFactory factory,List<LoadBalancerFeignRequestTransformer> transformers) {
        super(delegate,balancer,factory,transformers);this.delegate=delegate;
    }
    @Override public void close() { delegate.close(); }
}
