package com.taskflow.scheduler.plan.service;

import java.time.Instant;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest.Configuration;
import com.taskflow.scheduler.plan.model.PlanRecords.Calculation;

public interface IPlanCalculationService {
    Calculation calculate(Configuration configuration, boolean deleted, Instant from);
}
