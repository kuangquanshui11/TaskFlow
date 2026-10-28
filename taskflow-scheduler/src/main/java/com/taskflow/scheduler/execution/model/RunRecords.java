package com.taskflow.scheduler.execution.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Snapshot;

public final class RunRecords {
    private RunRecords() { }
    public record RunRow(UUID id, long jobId, long configVersion, UUID requestKey, String fingerprint,
            String state, String snapshotJson, int currentAttempt, String resultCode, String message,
            Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Attempt(UUID id, UUID runId, int attemptNumber, long leaseVersion, String state,
            int dispatchCount, Instant nextDispatchAt, Instant expiresAt, Instant startedAt,
            Instant finishedAt, String resultCode, String message) {
        public boolean terminal() { return java.util.Set.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(state); }
    }
    public record RunView(UUID id, long jobId, long configVersion, UUID requestKey, String state,
            Snapshot snapshot, List<Attempt> attempts, String resultCode, String message,
            Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Summary(UUID id, long jobId, long configVersion, String state, int attemptCount,
            String resultCode, String message, Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Page(List<Summary> records, long total, int page, int pageSize) { }
}
