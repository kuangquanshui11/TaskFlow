package com.taskflow.worker.dispatch.service.impl;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import com.rabbitmq.client.Channel;
import com.taskflow.worker.dispatch.config.DispatchProperties;
import com.taskflow.worker.dispatch.dto.DispatchEvent;
import com.taskflow.worker.dispatch.service.IDispatchConsumerService;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.service.IExecutionService;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "taskflow.dispatch.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class DispatchConsumerServiceImpl implements IDispatchConsumerService {
    private final IExecutionService executions;
    private final JsonMapper json;
    private final Validator validator;
    private final DispatchProperties properties;
    public DispatchConsumerServiceImpl(IExecutionService executions, JsonMapper json, Validator validator, DispatchProperties properties) {
        this.executions = executions; this.json = json; this.validator = validator; this.properties = properties;
    }
    @Override
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        DispatchEvent event;
        try {
            if (message.getBody().length > 131072 || !"taskflow.dispatch.v1".equals(message.getMessageProperties().getType()))
                throw new IllegalArgumentException("Invalid dispatch envelope");
            event = json.readValue(message.getBody(), DispatchEvent.class);
            if (event == null || !validator.validate(event).isEmpty()
                    || !event.eventId().equals(event.command().attemptId())
                    || !event.eventId().toString().equals(message.getMessageProperties().getMessageId()))
                throw new IllegalArgumentException("Invalid dispatch event");
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            channel.basicReject(tag, false); return;
        }
        // 接收重试只包围 Inbox 提交，不包含 ACK；ACK 失败交回代理重投并以相同 Attempt 去重。
        for (int attempt = 1; attempt <= properties.maxConsumeAttempts(); attempt++) {
            try {
                executions.insert(event.command());
            } catch (RuntimeException failed) {
                boolean permanent = failed instanceof ExecutionException rejected && rejected.status() != 429;
                if (permanent || attempt == properties.maxConsumeAttempts()
                        || !event.command().expiresAt().isAfter(Instant.now())) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dispatch sent to dead letter queue. eventId={}, type={}",
                            event.eventId(), failed.getClass().getSimpleName());
                    channel.basicReject(tag, false); return;
                }
                try { TimeUnit.MILLISECONDS.sleep(properties.consumeRetryDelay().toMillis()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); channel.basicNack(tag, false, true); return;
                }
                continue;
            }
            channel.basicAck(tag, false); return;
        }
    }
}
