package com.taskflow.scheduler.plan.dto;

import java.time.Instant;
import com.taskflow.scheduler.plan.model.PlanRecords.Plan;
import com.taskflow.scheduler.plan.model.PlanRecords.CalculationState;

public record PlanResponse(long jobId, long configVersion, boolean deleted, boolean enabled, String snapshotJson,
        Instant updatedAt, Instant nextFireTime, Instant calculatedAt, CalculationState calculationState,
        boolean automaticTriggerEnabled) {
    public static PlanResponse from(Plan plan, boolean automaticTriggerEnabled) {
        return new PlanResponse(plan.jobId(), plan.configVersion(), plan.deleted(), plan.enabled(), plan.snapshotJson(),
                plan.updatedAt(), plan.nextFireTime(), plan.calculatedAt(), plan.calculationState(), automaticTriggerEnabled);
    }
}
