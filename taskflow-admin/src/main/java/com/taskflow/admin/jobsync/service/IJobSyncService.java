package com.taskflow.admin.jobsync.service;

import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.jobsync.model.JobSyncRecords.Status;
import com.taskflow.admin.jobsync.model.PlanView;

public interface IJobSyncService {
    void enqueue(JobDefinition job, boolean deleted);
    void dispatchPending();
    Status selectByJobId(long jobId);
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('job-sync:retry')")
    Status retry(long jobId, long configVersion);
    PlanView selectPlanByJobId(long jobId);
}
