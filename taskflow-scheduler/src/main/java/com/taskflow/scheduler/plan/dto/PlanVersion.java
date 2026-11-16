package com.taskflow.scheduler.plan.dto;

public record PlanVersion(long jobId, long configVersion, boolean deleted, boolean enabled, String fingerprint) {
    public record Page(long upperJobId, boolean hasMore, java.util.List<PlanVersion> replicas) { }
}
