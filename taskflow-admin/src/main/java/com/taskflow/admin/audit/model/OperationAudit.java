package com.taskflow.admin.audit.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OperationAudit(UUID id, String actor, Action action, Long jobId, Long configVersion,
        UUID requestKey, UUID runId, Status status, String resultCode, String requestId, String traceId,
        Instant occurredAt, Instant updatedAt, int checkCount) {
    public enum Action { JOB_INSERT, JOB_UPDATE, JOB_ENABLE, JOB_DISABLE, JOB_DELETE, RUN_TRIGGER }
    public enum Status { PENDING, APPLIED, REJECTED, UNCONFIRMED }
    public record Query(Instant from, Instant to, String actor, Action action, Long jobId, UUID runId,
            UUID requestKey, int page, int pageSize) { }
    public record Page(List<OperationAudit> records, int page, int pageSize, boolean hasMore) { }
}
