package com.taskflow.worker.execution.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AttemptEvents(UUID runId, UUID attemptId, String workerId, List<Event> events, boolean truncated, ReportFailure reportFailure, Timing timing) {
    public record Timing(Long handlerMillis, Long resultReportMillis) { }
    public record ReportFailure(String code, int attempts, Instant observedAt) { }
    public record Event(long id, String eventType, Instant occurredAt, Integer reportNumber, String state, String resultCode) {}
}
