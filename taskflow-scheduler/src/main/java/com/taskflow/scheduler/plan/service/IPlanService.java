package com.taskflow.scheduler.plan.service;

import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.model.PlanRecords.*;

public interface IPlanService {
    Acknowledgement update(PlanSyncRequest request);
    Plan selectByJobId(long jobId);
    void initializePending();
}
