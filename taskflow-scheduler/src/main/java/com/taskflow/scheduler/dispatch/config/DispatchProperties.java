package com.taskflow.scheduler.dispatch.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taskflow.dispatch")
public record DispatchProperties(String transport, String exchange, String queue, String routingKey,
        String deadLetterExchange, String deadLetterQueue, String deadLetterRoutingKey,
        Duration confirmTimeout, Duration claimLease, Duration retryDelay, Duration maxRetryDelay,
        int maxPublishAttempts, boolean relayEnabled, Duration nodeRecheckDelay) {
    public DispatchProperties {
        if (!Set.of("http", "rabbitmq").contains(transport)
                || exchange == null || exchange.isBlank() || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()
                || deadLetterExchange == null || deadLetterExchange.isBlank()
                || deadLetterQueue == null || deadLetterQueue.isBlank()
                || deadLetterRoutingKey == null || deadLetterRoutingKey.isBlank()
                || confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()
                || claimLease == null || claimLease.compareTo(confirmTimeout.plusSeconds(5)) <= 0
                || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || maxRetryDelay == null || maxRetryDelay.compareTo(retryDelay) < 0
                || maxPublishAttempts < 1 || maxPublishAttempts > 20
                || nodeRecheckDelay == null || nodeRecheckDelay.compareTo(Duration.ofMillis(100)) < 0
                || nodeRecheckDelay.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("派发 Outbox 配置不合法");
    }
    public boolean rabbitmq() { return "rabbitmq".equals(transport); }
}
