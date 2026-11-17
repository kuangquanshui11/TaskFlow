package com.taskflow.worker.execution.service;

import java.util.UUID;
import java.util.function.Consumer;
import com.taskflow.worker.execution.dto.AttemptLogs;

public interface IAttemptLogService {
    Consumer<String> progress(UUID runId, UUID attemptId);
    void finished(UUID runId, UUID attemptId, String resultCode);
    AttemptLogs select(UUID runId, UUID attemptId, String workerId, int offset);
}
