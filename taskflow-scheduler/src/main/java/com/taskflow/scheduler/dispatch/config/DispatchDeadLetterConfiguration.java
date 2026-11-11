package com.taskflow.scheduler.dispatch.config;

import java.util.List;
import com.taskflow.scheduler.dispatch.service.IDispatchDeadLetterService;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taskflow.dispatch.dead-letter.enabled", havingValue = "true")
public class DispatchDeadLetterConfiguration {
    @Bean(defaultCandidate = false, destroyMethod = "destroy")
    CachingConnectionFactory deadLetterConnection(@Value("${taskflow.dispatch.dead-letter.addresses}") String addresses,
            @Value("${taskflow.dispatch.dead-letter.virtual-host}") String vhost,
            @Value("${taskflow.dispatch.dead-letter.username}") String username,
            @Value("${taskflow.dispatch.dead-letter.password}") String password) {
        if (addresses.isBlank() || vhost.isBlank() || username.isBlank() || password.isBlank())
            throw new IllegalArgumentException("Dead letter reader requires dedicated credentials and a Broker address");
        var connection = new CachingConnectionFactory();
        connection.setAddresses(addresses);
        connection.setVirtualHost(vhost);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setConnectionTimeout(3000);
        return connection;
    }

    @Bean
    SimpleMessageListenerContainer dispatchDeadLetters(@Qualifier("deadLetterConnection") CachingConnectionFactory connection,
            IDispatchDeadLetterService service, @Value("${taskflow.dispatch.dead-letter.queues}") List<String> queues,
            @Value("${taskflow.dispatch.dead-letter.retry-delay-ms}") long retryDelay) {
        if (queues.isEmpty() || queues.size() > 100 || queues.stream().anyMatch(queue -> !queue.matches("taskflow\\.[a-z0-9_.-]{1,155}\\.dead\\.v1"))
                || retryDelay < 100 || retryDelay > 10000)
            throw new IllegalArgumentException("Dead letter reader requires an explicit queue list and valid retry delay");
        var container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(queues.toArray(String[]::new));
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(false);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            long tag = message.getMessageProperties().getDeliveryTag();
            try {
                service.record(message);
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
                try { Thread.sleep(retryDelay); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                channel.basicNack(tag, false, true);
                return;
            }
            channel.basicAck(tag, false);
        });
        return container;
    }
}
