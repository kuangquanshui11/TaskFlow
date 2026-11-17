package com.taskflow.scheduler.execution.service;

import java.util.UUID;
import com.taskflow.scheduler.execution.dto.AttemptLogs;
import com.taskflow.scheduler.execution.dto.AttemptEvents.Timeline;

public interface IAttemptEventService {
    Timeline select(UUID runId, UUID attemptId);
    AttemptLogs selectLogs(UUID runId, UUID attemptId, int offset);
}
