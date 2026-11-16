package com.taskflow.admin.reconciliation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReconciliationRecords {
    private ReconciliationRecords() { }

    public record Source(long jobId, long configVersion, boolean deleted, boolean enabled, UUID eventId, String fingerprint) {
        public String difference(Replica replica) {
            if (eventId == null) return "SOURCE_MISSING";
            if ("INVALID".equals(fingerprint)) return "SOURCE_INVALID";
            if (replica == null) return "MISSING";
            if (replica.configVersion > configVersion) return "AHEAD";
            if (replica.configVersion < configVersion) return "BEHIND";
            if (replica.deleted != deleted || replica.enabled != enabled || "INVALID".equals(replica.fingerprint)
                    || (replica.fingerprint != null && !replica.fingerprint.equals(fingerprint))) return "CONFLICT";
            return "MATCH";
        }

        public boolean matchesObservation(Source observed) {
            return jobId == observed.jobId && configVersion == observed.configVersion && deleted == observed.deleted
                    && enabled == observed.enabled && java.util.Objects.equals(eventId, observed.eventId)
                    && !"INVALID".equals(fingerprint)
                    && (observed.fingerprint == null || observed.fingerprint.equals(fingerprint));
        }
    }
    public record Replica(long jobId, long configVersion, boolean deleted, boolean enabled, String fingerprint) {
        public Replica(long jobId, long configVersion, boolean deleted, boolean enabled) {
            this(jobId, configVersion, deleted, enabled, null);
        }
    }
    public record ReplicaPage(long upperJobId, boolean hasMore, List<Replica> replicas) { }
    public record Check(UUID id, UUID scanId, Source source, Replica replica, String difference,
                        String errorCode, Instant checkedAt, UUID repairRequestId) {
        public long jobId() { return source == null ? replica.jobId() : source.jobId(); }
    }
    public record Scan(UUID requestId, long afterJobId, int batchSize, long nextAfterJobId, boolean hasMore,
                       String state, String actor, Instant startedAt, Instant leaseUntil, Instant finishedAt,
                       List<Check> checks) { }
    public record Repair(UUID requestId, UUID checkId, UUID eventId, String outcome, String actor, Instant createdAt) { }
    public enum Phase { ADMIN, SCHEDULER, COMPLETED }
    public record Round(UUID id, Phase phase, long afterJobId, long adminUpperJobId, Long schedulerUpperJobId,
                        long scannedCount, long differenceCount, String lastErrorCode, Instant startedAt, Instant finishedAt) { }
    public record AutomaticStatus(boolean enabled, Round round) { }
    public record DifferencePage(List<Check> checks, Long nextAfterJobId) { }
}
