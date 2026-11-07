package com.taskflow.scheduler.remote.config;

import java.util.List;
import com.taskflow.scheduler.node.config.WorkerNodeProperties;
import com.taskflow.scheduler.remote.client.WorkerAttemptEventsRemote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

// 仅由 taskflow-worker 的 LoadBalancer 子上下文装配。
public class WorkerLoadBalancerConfiguration {
    @Bean public ServiceInstanceListSupplier workerInstances(DiscoveryClient discovery,WorkerNodeProperties nodes,
            @Value("${" + LoadBalancerClientFactory.PROPERTY_NAME + "}") String serviceId) {
        return new ServiceInstanceListSupplier() {
            @Override public String getServiceId() { return serviceId; }
            @Override public Flux<List<ServiceInstance>> get() {
                return instancesFor(nodes.targetWorkerId());
            }
            @Override public Flux<List<ServiceInstance>> get(Request request) {
                if (request.getContext() instanceof RequestDataContext context) {
                    var workerId = context.getClientRequest().getHeaders().getFirst(WorkerAttemptEventsRemote.TARGET_HEADER);
                    if (workerId != null) return instancesFor(workerId);
                }
                return get();
            }
            private Flux<List<ServiceInstance>> instancesFor(String workerId) {
                return Flux.defer(()->Flux.just(discovery.getInstances(serviceId).stream()
                        .filter(i->workerId.equals(i.getMetadata().get("worker-id"))).toList()));
            }
        };
    }
}
