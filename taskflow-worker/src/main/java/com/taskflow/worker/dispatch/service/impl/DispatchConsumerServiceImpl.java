package com.taskflow.worker.dispatch.service.impl;

import com.taskflow.worker.observability.config.AsyncTracing;
import com.taskflow.worker.observability.config.AsyncTracing.Context;
import java.io.IOException;
import java.util.Map;
import java.time.Duration;
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
    private final AsyncTracing tracing;
    private final IExecutionService executions;
    private final JsonMapper json;
    private final Validator validator;
    private final DispatchProperties properties;
    public DispatchConsumerServiceImpl(IExecutionService executions, JsonMapper json, Validator validator, DispatchProperties properties, AsyncTracing tracing) {
        this.executions = executions; this.json = json; this.validator = validator; this.properties = properties;
        this.tracing = tracing;
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
            String id = message.getMessageProperties().getMessageId();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dispatch rejected. eventId={}, reason=INVALID_DISPATCH_ENVELOPE",
                    id != null && id.matches("[a-f0-9-]{36}") ? id : "unknown");
            channel.basicReject(tag, false); return;
        }
        var headers = message.getMessageProperties().getHeaders();
        var parent = new Context(traceHeader(headers.get("traceparent")), traceHeader(headers.get("tracestate")));
        try (var trace = tracing.start("dispatch.consume", parent, Map.of("runId", event.command().runId(),
                "attemptId", event.command().attemptId(), "eventId", event.eventId()))) {
            accept(event, channel, tag);
        }
    }

    private static String traceHeader(Object value) {
        return value instanceof String header && header.length() <= 512 ? header : null;
    }

    private void accept(DispatchEvent event, Channel channel, long tag) throws IOException {
        int failures = 0;
        boolean waitingForCapacity = false;
        // prefetch=1 时保留当前 delivery 即形成背压，不通过反复 requeue 消耗 Broker 投递次数。
        // 容量等待不计入故障预算；ACK 不放入 insert 的 try 块，失败后由原 Attempt 去重。
        while (channel.isOpen()) {
            try {
                executions.insert(event.command());
            } catch (RuntimeException failed) {
                boolean busy = failed instanceof ExecutionException rejected
                        && rejected.status() == 429 && "WORKER_BUSY".equals(rejected.code());
                boolean expired = !event.command().expiresAt().isAfter(Instant.now());
                boolean leaseExpired = failed instanceof ExecutionException rejected
                        && "LEASE_EXPIRED".equals(rejected.code());
                boolean permanent = failed instanceof ExecutionException && !busy && !leaseExpired;
                if (!permanent && (leaseExpired || expired)) {
                    // 过期命令不再执行，也不进入死信。权威 Attempt 仍由 Scheduler 的租约扫描收尾。
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Expired dispatch acknowledged without execution. eventId={}", event.eventId());
                    channel.basicAck(tag, false); return;
                }
                if (permanent || (!busy && ++failures >= properties.maxConsumeAttempts())) {
                    String reason = failed instanceof ExecutionException error ? error.code() : "INBOX_RETRY_EXHAUSTED";
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dispatch rejected. eventId={}, runId={}, reason={}",
                            event.eventId(), event.command().runId(), reason);
                    channel.basicReject(tag, false); return;
                }
                if (busy && !waitingForCapacity) {
                    waitingForCapacity = true;
                    org.slf4j.LoggerFactory.getLogger(getClass()).info("Dispatch waiting for Worker capacity. eventId={}", event.eventId());
                }
                var remaining = Duration.between(Instant.now(), event.command().expiresAt());
                var delay = remaining.compareTo(properties.consumeRetryDelay()) < 0 ? remaining : properties.consumeRetryDelay();
                try { if (delay.isPositive()) TimeUnit.NANOSECONDS.sleep(delay.toNanos()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); channel.basicNack(tag, false, true); return;
                }
                continue;
            }
            channel.basicAck(tag, false); return;
        }
    }
}
