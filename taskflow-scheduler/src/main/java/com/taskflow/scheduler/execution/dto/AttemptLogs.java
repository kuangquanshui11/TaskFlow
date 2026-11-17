package com.taskflow.scheduler.execution.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.taskflow.scheduler.execution.dto.AttemptEvents.WorkerStatus;

public record AttemptLogs(UUID runId, UUID attemptId, String workerId, WorkerStatus workerStatus,
                          List<Entry> entries, Integer nextOffset) {
    public record Entry(UUID runId, UUID attemptId, Instant occurredAt, String level, String message) { }
    public record WorkerPage(UUID runId, UUID attemptId, String workerId, List<Entry> entries, Integer nextOffset) { }
}
