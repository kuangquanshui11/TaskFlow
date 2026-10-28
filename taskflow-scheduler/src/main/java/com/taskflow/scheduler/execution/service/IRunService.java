package com.taskflow.scheduler.execution.service;

import java.util.UUID;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.model.RunRecords.*;

public interface IRunService {
    RunView insert(Create request);
    UUID insertScheduled(Snapshot snapshot, java.time.Instant scheduledAt, java.time.Instant createdAt);
    RunView selectById(UUID id);
    RunView selectByRequestKey(UUID key);
    Page select(long jobId, int page, int pageSize);
    void updateResult(Result result);
    void dispatchPending();
}
