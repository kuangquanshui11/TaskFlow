package com.taskflow.scheduler.plan.service;

import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.dto.PlanVersion;
import com.taskflow.scheduler.plan.model.PlanRecords.*;

public interface IPlanService {
    java.util.List<PlanVersion> selectVersions(java.util.List<Long> ids);
    PlanVersion.Page selectReplicaPage(long afterJobId, Long upperJobId, int batchSize);
    Acknowledgement update(PlanSyncRequest request);
    Plan selectByJobId(long jobId);
    void initializePending();
}
