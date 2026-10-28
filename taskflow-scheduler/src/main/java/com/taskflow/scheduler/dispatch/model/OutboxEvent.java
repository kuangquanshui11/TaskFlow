package com.taskflow.scheduler.dispatch.model;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID eventId, UUID attemptId, String state, String messageJson,
        Instant expiresAt, int publishAttempts, Instant nextPublishAt, long claimVersion,
        Instant claimUntil, String lastErrorCode) { }
