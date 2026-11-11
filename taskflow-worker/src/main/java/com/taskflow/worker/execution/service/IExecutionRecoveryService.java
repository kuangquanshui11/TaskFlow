package com.taskflow.worker.execution.service;

import java.util.UUID;
import com.taskflow.worker.execution.model.ExecutionCompletion;

public interface IExecutionRecoveryService {
    boolean tryTrack(UUID attemptId);
    void release(UUID attemptId);
    void defer(ExecutionCompletion completion);
    void scan();
}
