package com.taskflow.worker.execution.service.impl;

import java.util.UUID;
import com.taskflow.worker.execution.dto.AttemptEvents;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.service.IAttemptEventService;
import com.taskflow.worker.node.config.WorkerRegistrationProperties;
import org.springframework.stereotype.Service;

@Service
public class AttemptEventServiceImpl implements IAttemptEventService {
    private static final int LIMIT = 100;
    private final ExecutionMapper executions;
    private final WorkerRegistrationProperties registration;

    public AttemptEventServiceImpl(ExecutionMapper executions, WorkerRegistrationProperties registration) {
        this.executions = executions;
        this.registration = registration;
    }

    @Override
    public AttemptEvents select(UUID runId, UUID attemptId, String workerId) {
        if (!registration.workerId().equals(workerId))
            throw new ExecutionException(409, "WORKER_ID_MISMATCH", "请求目标与本 Worker 标识不一致");
        var events = executions.selectAttemptEvents(runId, attemptId, workerId, LIMIT + 1);
        return new AttemptEvents(runId, attemptId, workerId, events.stream().limit(LIMIT).toList(), events.size() > LIMIT);
    }
}
