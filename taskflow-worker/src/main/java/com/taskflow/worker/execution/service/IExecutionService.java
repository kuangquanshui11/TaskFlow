package com.taskflow.worker.execution.service;

import java.util.UUID;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;

public interface IExecutionService {
    Accepted insert(Dispatch request);
    Accepted selectById(UUID attemptId);
    void executePending();
    void reportPending();
    void recover();
    com.taskflow.worker.execution.model.ExecutionLoad selectLoad();
}
