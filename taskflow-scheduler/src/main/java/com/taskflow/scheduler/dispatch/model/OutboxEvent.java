package com.taskflow.scheduler.dispatch.model;

import com.taskflow.scheduler.observability.config.AsyncTracing.Context;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.scheduler.node.model.DispatchTarget;

public record OutboxEvent(UUID eventId, UUID attemptId, String state, String messageJson,
        Instant expiresAt, int publishAttempts, Instant nextPublishAt, long claimVersion,
        Instant claimUntil, String lastErrorCode, DispatchTarget target, Context traceContext) { }
