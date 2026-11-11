package com.taskflow.scheduler.execution.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AttemptEvents {
    private AttemptEvents() {}

    public record SchedulerEvent(long id, String workerId, String eventType, Instant occurredAt,
            long leaseVersion, Integer sequenceNumber, String state, String resultCode, Instant deadlineAt) {}

    public record WorkerEvent(long id, String eventType, Instant occurredAt, Integer reportNumber,
            String state, String resultCode) {}

    public record Batch<T>(List<T> events, boolean truncated) {}

    public record ReportFailure(String code, int attempts, Instant observedAt) { }

    public record WorkerEvents(UUID runId, UUID attemptId, String workerId, List<WorkerEvent> events, Boolean truncated,
                               ReportFailure reportFailure) {
        public WorkerEvents(UUID runId, UUID attemptId, String workerId, List<WorkerEvent> events, Boolean truncated) {
            this(runId, attemptId, workerId, events, truncated, null);
        }
    }

    public enum WorkerStatus { AVAILABLE, UNAVAILABLE, NOT_ASSIGNED }

    public record Timeline(UUID runId, UUID attemptId, String workerId, Batch<SchedulerEvent> scheduler,
            WorkerStatus workerStatus, Batch<WorkerEvent> worker) {}
}
