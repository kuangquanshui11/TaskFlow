package com.taskflow.scheduler.remote;

import java.time.Duration;
import java.util.List;
import com.taskflow.scheduler.node.config.WorkerNodeProperties;
import com.taskflow.scheduler.remote.config.WorkerLoadBalancerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClientSpecification;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkerDiscoveryTests {
    @Test void realLoadBalancerChildContextKeepsFixedWorkerInboxAffinity() {
        var target = new DefaultServiceInstance("target", "taskflow-worker", "localhost", 8082, false);
        target.getMetadata().put("worker-id", "worker-default");
        var other = new DefaultServiceInstance("other", "taskflow-worker", "localhost", 9082, false);
        other.getMetadata().put("worker-id", "another-worker");
        var missingIdentity = new DefaultServiceInstance("missing", "taskflow-worker", "localhost", 9083, false);
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("taskflow-worker")).thenReturn(List.of(other, missingIdentity, target));
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.registerBean(DiscoveryClient.class, () -> discovery);
            parent.registerBean(WorkerNodeProperties.class, () -> new WorkerNodeProperties(
                    "test-node-token", "worker-default", Duration.ofSeconds(30), true));
            var factory = new LoadBalancerClientFactory(new LoadBalancerClientsProperties());
            factory.setApplicationContext(parent);
            factory.setConfigurations(List.of(new LoadBalancerClientSpecification("taskflow-worker",
                    new Class<?>[] { WorkerLoadBalancerConfiguration.class })));
            parent.registerBean(LoadBalancerClientFactory.class, () -> factory);
            parent.refresh();
            try {
                var supplier = factory.getInstance("taskflow-worker", ServiceInstanceListSupplier.class);
                assertThat(supplier.getServiceId()).isEqualTo("taskflow-worker");
                assertThat(supplier.get().blockFirst()).containsExactly(target);
                when(discovery.getInstances("taskflow-worker")).thenReturn(List.of(other, missingIdentity));
                assertThat(supplier.get().blockFirst()).isEmpty();
            } finally {
                factory.destroy();
            }
        }
    }
}
