package com.taskflow.worker.execution.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AttemptEvents(UUID runId, UUID attemptId, String workerId, List<Event> events, boolean truncated) {
    public record Event(long id, String eventType, Instant occurredAt, Integer reportNumber, String state, String resultCode) {}
}
