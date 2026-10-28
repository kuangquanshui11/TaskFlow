package com.taskflow.worker.dispatch.config;

import com.taskflow.worker.dispatch.service.IDispatchConsumerService;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taskflow.dispatch.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class DispatchListenerConfiguration {
    @Bean
    public SimpleMessageListenerContainer dispatchListener(ConnectionFactory connectionFactory,
            DispatchProperties properties, IDispatchConsumerService consumer) {
        var container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(properties.queue());
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1); container.setPrefetchCount(1);
        container.setMissingQueuesFatal(false);
        container.setDefaultRequeueRejected(true);
        container.setAutoStartup(properties.consumerEnabled());
        container.setMessageListener((ChannelAwareMessageListener) consumer::consume);
        return container;
    }
}
