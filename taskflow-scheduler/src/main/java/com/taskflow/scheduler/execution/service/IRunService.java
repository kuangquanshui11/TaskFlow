package com.taskflow.scheduler.execution.service;

import java.util.UUID;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.model.OutcomeReview;
import com.taskflow.scheduler.execution.model.RunRecords.*;

public interface IRunService {
    RunView insert(Create request);
    RunView insertRerun(Rerun request);
    UUID insertScheduled(Snapshot snapshot, java.time.Instant scheduledAt, java.time.Instant createdAt);
    RunView selectById(UUID id);
    RunView selectByRequestKey(UUID key);
    Page select(long jobId, int page, int pageSize);
    Page selectGlobal(com.taskflow.scheduler.execution.dto.RunQuery query);
    Overview selectSummary(java.time.Instant from, java.time.Instant to);
    void updateResult(Result result);
    OutcomeReview.Review updateOutcomeReview(ReviewOutcome request);
    void dispatchPending();
}
