package com.taskflow.scheduler.dispatch.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.model.OutboxEvent;
import com.taskflow.scheduler.dispatch.service.IDispatchPublisher;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "taskflow.dispatch.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitDispatchPublisher implements IDispatchPublisher {
    private final RabbitTemplate rabbit;
    private final DispatchProperties properties;
    public RabbitDispatchPublisher(RabbitTemplate rabbit, DispatchProperties properties) { this.rabbit = rabbit; this.properties = properties; }
    @Override
    public void publish(OutboxEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("MQ publish inside SQL transaction");
        if (event.target() == null || event.target().exchange() == null || event.target().routingKey() == null)
            throw new IllegalStateException("Dispatch route is missing");
        var message = MessageBuilder.withBody(event.messageJson().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(event.eventId().toString())
                .setType("taskflow.dispatch.v1").build();
        var correlation = new CorrelationData(event.eventId() + ":" + event.claimVersion());
        try {
            rabbit.send(event.target().exchange(), event.target().routingKey(), message, correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            // mandatory return 先于对应 confirm 完成；ack 但不可路由也必须保留重发。
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("MQ publish rejected or unroutable");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MQ publish interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException("MQ publish unconfirmed", failed);
        }
    }
}
