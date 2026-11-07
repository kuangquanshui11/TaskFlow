package com.taskflow.scheduler.execution.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Snapshot;

public final class RunRecords {
    private RunRecords() { }
    public record RunRow(UUID id, long jobId, long configVersion, UUID requestKey, String fingerprint,
            String state, String snapshotJson, int currentAttempt, String resultCode, String message,
            Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Attempt(UUID id, UUID runId, int attemptNumber, long leaseVersion, String state,
            int dispatchCount, Instant nextDispatchAt, Instant expiresAt, Instant startedAt,
            Instant finishedAt, String resultCode, String message,
            Instant leaseUntil, String ownerWorkerId, UUID ownerSessionId, String targetWorkerId) {
        public Attempt(UUID id, UUID runId, int attemptNumber, long leaseVersion, String state,
                int dispatchCount, Instant nextDispatchAt, Instant expiresAt, Instant startedAt,
                Instant finishedAt, String resultCode, String message, Instant leaseUntil, String ownerWorkerId, UUID ownerSessionId) {
            this(id, runId, attemptNumber, leaseVersion, state, dispatchCount, nextDispatchAt, expiresAt,
                    startedAt, finishedAt, resultCode, message, leaseUntil, ownerWorkerId, ownerSessionId, null);
        }
        public Attempt(UUID id, UUID runId, int attemptNumber, long leaseVersion, String state,
                int dispatchCount, Instant nextDispatchAt, Instant expiresAt, Instant startedAt,
                Instant finishedAt, String resultCode, String message) {
            this(id, runId, attemptNumber, leaseVersion, state, dispatchCount, nextDispatchAt, expiresAt,
                    startedAt, finishedAt, resultCode, message, null, null, null, null);
        }
        public boolean validAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now) && (leaseUntil == null || leaseUntil.isAfter(now));
        }
        public boolean terminal() { return java.util.Set.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(state); }
    }
    public record RunView(UUID id, long jobId, long configVersion, UUID requestKey, String state,
            Snapshot snapshot, List<Attempt> attempts, String resultCode, String message,
            Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Summary(UUID id, long jobId, long configVersion, String state, int attemptCount,
            String resultCode, String message, Instant createdAt, Instant updatedAt, String triggerType, Instant scheduledAt) { }
    public record Page(List<Summary> records, long total, int page, int pageSize) { }
    public record Overview(Instant from, Instant to, Instant observedAt, long total,
            java.util.Map<String, Long> stateCounts, long finished, long succeeded, long failed,
            java.math.BigDecimal successRate, long attemptTotal, long retriedRunCount,
            Latency latency, List<FailureGroup> failures) { }
    public record Latency(Long avgMillis, Long p95Millis, int sampled, boolean truncated) { }
    public record FailureGroup(long jobId, String resultCode, long runCount, Instant lastFailedAt) {
        @JsonProperty("jobIdText")
        public String jobIdText() { return Long.toString(jobId); }
    }
    public record StateCount(String state, long count, long timedCount, java.math.BigDecimal totalMillis) { }
    public record AttemptTotals(long attemptTotal, long retriedRunCount) { }
}
