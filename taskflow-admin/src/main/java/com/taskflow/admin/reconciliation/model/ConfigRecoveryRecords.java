package com.taskflow.admin.reconciliation.model;

public final class ConfigRecoveryRecords {
    private ConfigRecoveryRecords() { }

    public record Bounds(long upperJobId) { }
    public record Batch(long nextAfterJobId, boolean hasMore, int processed) { }
}
