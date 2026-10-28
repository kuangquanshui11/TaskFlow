package com.taskflow.scheduler.plan.model;

import java.time.Instant;
import java.util.UUID;

public final class PlanRecords {
    private PlanRecords() { }
    public record Plan(long jobId, long configVersion, boolean deleted, boolean enabled,
                       String snapshotJson, Instant updatedAt, Instant nextFireTime,
                       Instant calculatedAt, CalculationState calculationState) { }
    public enum CalculationState { PENDING, READY, DISABLED, MANUAL, DELETED, NO_FUTURE_FIRE, INVALID }
    public record Calculation(Instant nextFireTime, Instant calculatedAt, CalculationState state) { }
    public record Receipt(UUID eventId, long jobId, long configVersion, String fingerprint) { }
    public record Acknowledgement(UUID eventId, long jobId, long configVersion, long acceptedVersion, String result) { }
}
