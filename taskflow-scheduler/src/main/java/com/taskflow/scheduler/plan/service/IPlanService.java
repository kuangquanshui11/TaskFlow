package com.taskflow.scheduler.plan.service;

import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.model.PlanRecords.*;

public interface IPlanService {
    java.util.List<com.taskflow.scheduler.plan.dto.PlanVersion> selectVersions(java.util.List<Long> ids);
    Acknowledgement update(PlanSyncRequest request);
    Plan selectByJobId(long jobId);
    void initializePending();
}
