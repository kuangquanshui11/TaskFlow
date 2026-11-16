package com.taskflow.worker.execution.service.impl;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.worker.execution.config.ExecutionProperties;
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
    private final ExecutionProperties properties;

    public AttemptEventServiceImpl(ExecutionMapper executions, WorkerRegistrationProperties registration, ExecutionProperties properties) {
        this.executions = executions;
        this.registration = registration;
        this.properties = properties;
    }

    @Override
    public AttemptEvents select(UUID runId, UUID attemptId, String workerId) {
        if (!registration.workerId().equals(workerId))
            throw new ExecutionException(409, "WORKER_ID_MISMATCH", "请求目标与本 Worker 标识不一致");
        var events = executions.selectAttemptEvents(runId, attemptId, workerId, LIMIT + 1);
        var now = Instant.now();
        var failure = executions.selectReportFailure(runId, attemptId, workerId, properties.maxReportAttempts(),
                now.minus(properties.connectTimeout()).minus(properties.requestTimeout()), now).orElse(null);
        return new AttemptEvents(runId, attemptId, workerId, events.stream().limit(LIMIT).toList(), events.size() > LIMIT,
                failure, executions.selectTiming(runId, attemptId, workerId));
    }
}
