package com.taskflow.worker.dispatch.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.dispatch")
public record DispatchProperties(String transport, String exchange, String queue, String routingKey,
        String deadLetterExchange, String deadLetterQueue, String deadLetterRoutingKey,
        int maxConsumeAttempts, Duration consumeRetryDelay, boolean consumerEnabled) {
    public DispatchProperties {
        if (!Set.of("http", "rabbitmq").contains(transport)
                || exchange == null || exchange.isBlank() || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()
                || deadLetterExchange == null || deadLetterExchange.isBlank()
                || deadLetterQueue == null || deadLetterQueue.isBlank()
                || deadLetterRoutingKey == null || deadLetterRoutingKey.isBlank()
                || maxConsumeAttempts < 1 || maxConsumeAttempts > 10
                || consumeRetryDelay == null || consumeRetryDelay.isNegative() || consumeRetryDelay.isZero())
            throw new IllegalArgumentException("Worker 消息消费配置不合法");
    }
}
