package com.taskflow.worker.execution.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AttemptLogs(UUID runId, UUID attemptId, String workerId, List<Entry> entries, Integer nextOffset) {
    public record Entry(UUID runId, UUID attemptId, Instant occurredAt, String level, String message) { }
}
