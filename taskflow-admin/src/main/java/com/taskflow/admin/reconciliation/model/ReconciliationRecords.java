package com.taskflow.admin.reconciliation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReconciliationRecords {
    private ReconciliationRecords() { }

    public record Source(long jobId, long configVersion, boolean deleted, boolean enabled, UUID eventId) { }
    public record Replica(long jobId, long configVersion, boolean deleted, boolean enabled) { }
    public record Check(UUID id, UUID scanId, Source source, Replica replica, String difference,
                        String errorCode, Instant checkedAt, UUID repairRequestId) { }
    public record Scan(UUID requestId, long afterJobId, int batchSize, long nextAfterJobId, boolean hasMore,
                       String state, String actor, Instant startedAt, Instant leaseUntil, Instant finishedAt,
                       List<Check> checks) { }
    public record Repair(UUID requestId, UUID checkId, UUID eventId, String outcome, String actor, Instant createdAt) { }
}
