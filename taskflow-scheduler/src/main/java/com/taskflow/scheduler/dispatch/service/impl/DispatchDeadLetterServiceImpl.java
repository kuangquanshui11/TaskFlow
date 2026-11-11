package com.taskflow.scheduler.dispatch.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchEvent;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.DeadLetter;
import com.taskflow.scheduler.dispatch.mapper.DispatchFailureMapper;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.dispatch.service.IDispatchDeadLetterService;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DispatchDeadLetterServiceImpl implements IDispatchDeadLetterService {
    private final DispatchFailureMapper failures;
    private final DispatchOutboxMapper outbox;
    private final RunDataMapper runs;
    private final JsonMapper json;

    public DispatchDeadLetterServiceImpl(DispatchFailureMapper failures, DispatchOutboxMapper outbox, RunDataMapper runs,
            JsonMapper json) {
        this.failures = failures;
        this.outbox = outbox;
        this.runs = runs;
        this.json = json;
    }

    @Override
    @Transactional
    public void record(Message message) {
        var headers = message.getMessageProperties();
        var eventId = parseId(headers.getMessageId());
        var deaths = headers.getHeaders().get("x-death");
        var death = deaths instanceof java.util.List<?> entries && !entries.isEmpty()
                && entries.getFirst() instanceof java.util.Map<?, ?> first ? first : java.util.Map.of();
        var sourceQueue = safe(death.get("queue"), 180);
        var reason = safe(death.get("reason"), 64);
        var queue = headers.getConsumerQueue();
        var payloadHash = hash(message.getBody());
        // 同一投递可重复抵达。相同标识但不同正文必须保留为独立证据。
        var key = hash((queue + "\n" + eventId + "\n" + payloadHash).getBytes(StandardCharsets.UTF_8));
        UUID runId = null;
        UUID attemptId = null;
        var event = decode(message, eventId);
        if (event != null && sourceQueue != null && outbox.find(eventId, false)
                .filter(entry -> entry.target() != null && sourceQueue.equals(entry.target().queue())
                    && entry.messageJson() != null
                    && payloadHash.equals(hash(entry.messageJson().getBytes(StandardCharsets.UTF_8)))).isPresent()
                && runs.attempt(eventId).filter(row -> row.runId().equals(event.command().runId())
                    && row.leaseVersion() == event.command().leaseVersion()).isPresent()) {
            runId = event.command().runId();
            attemptId = eventId;
        }
        failures.record(new DeadLetter(key, runId, attemptId, eventId, queue, sourceQueue,
                reason == null ? "BROKER_REASON_UNKNOWN" : reason, payloadHash, Instant.now()));
    }

    private DispatchEvent decode(Message message, UUID eventId) {
        if (eventId == null || message.getBody().length > 131072
                || !"taskflow.dispatch.v1".equals(message.getMessageProperties().getType())) return null;
        try {
            var event = json.readValue(message.getBody(), DispatchEvent.class);
            return event != null && event.version() == 1 && eventId.equals(event.eventId()) && event.command() != null
                    && eventId.equals(event.command().attemptId()) ? event : null;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String safe(Object value, int maxLength) {
        var text = value == null ? "" : value.toString();
        return text.length() <= maxLength && text.matches("[a-zA-Z0-9_.:-]+") ? text : null;
    }

    private static UUID parseId(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
