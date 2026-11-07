package com.taskflow.scheduler.execution.service.impl;

import java.util.List;
import java.util.UUID;
import com.taskflow.scheduler.execution.client.WorkerAttemptEventClient;
import com.taskflow.scheduler.execution.dto.AttemptEvents.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.service.IAttemptEventService;
import org.springframework.stereotype.Service;

@Service
public class AttemptEventServiceImpl implements IAttemptEventService {
    private static final int LIMIT = 100;
    private static final Batch<WorkerEvent> NO_WORKER_EVENTS = new Batch<>(List.of(), false);
    private final RunDataMapper runs;
    private final WorkerAttemptEventClient worker;

    public AttemptEventServiceImpl(RunDataMapper runs, WorkerAttemptEventClient worker) {
        this.runs = runs;
        this.worker = worker;
    }

    @Override
    public Timeline select(UUID runId, UUID attemptId) {
        var attempt = runs.attempt(attemptId).filter(row -> runId.equals(row.runId()))
                .orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "该运行中未找到此 Attempt"));
        var events = runs.selectAttemptEvents(runId, attemptId, LIMIT + 1);
        var scheduler = new Batch<>(events.stream().limit(LIMIT).toList(), events.size() > LIMIT);
        var workerId = attempt.targetWorkerId() == null ? attempt.ownerWorkerId() : attempt.targetWorkerId();
        if (workerId == null) {
            return new Timeline(runId, attemptId, null, scheduler, WorkerStatus.NOT_ASSIGNED, NO_WORKER_EVENTS);
        }
        return worker.select(runId, attemptId, workerId, LIMIT)
                .map(batch -> new Timeline(runId, attemptId, workerId, scheduler, WorkerStatus.AVAILABLE, batch))
                .orElseGet(() -> new Timeline(runId, attemptId, workerId, scheduler, WorkerStatus.UNAVAILABLE, NO_WORKER_EVENTS));
    }
}
