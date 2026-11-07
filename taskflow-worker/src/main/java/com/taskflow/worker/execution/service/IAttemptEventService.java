package com.taskflow.worker.execution.service;

import java.util.UUID;
import com.taskflow.worker.execution.dto.AttemptEvents;

public interface IAttemptEventService {
    AttemptEvents select(UUID runId, UUID attemptId, String workerId);
}
