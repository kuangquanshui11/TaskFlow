package com.taskflow.scheduler.execution.service.impl;

import java.util.List;
import java.util.UUID;
import com.taskflow.scheduler.execution.client.WorkerAttemptEventClient;
import com.taskflow.scheduler.execution.dto.AttemptEvents.*;
import com.taskflow.scheduler.execution.dto.AttemptLogs;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.Attempt;
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
        var attempt = requireAttempt(runId, attemptId);
        var workerId = assignedWorker(attempt);
        var events = runs.selectAttemptEvents(runId, attemptId, LIMIT + 1);
        var scheduler = new Batch<>(events.stream().limit(LIMIT).toList(), events.size() > LIMIT);
        Long dispatchMillis = RunServiceImpl.elapsedMillis(attempt.startedAt(), runs.firstLeaseAcquiredAt(runId, attemptId));
        if (workerId == null) {
            return new Timeline(runId, attemptId, null, scheduler, WorkerStatus.NOT_ASSIGNED, NO_WORKER_EVENTS, dispatchMillis, null);
        }
        return worker.snapshot(runId, attemptId, workerId, LIMIT)
                .map(response -> new Timeline(runId, attemptId, workerId, scheduler, WorkerStatus.AVAILABLE,
                        new Batch<>(response.events(), response.truncated()), dispatchMillis, response.timing()))
                .orElseGet(() -> new Timeline(runId, attemptId, workerId, scheduler, WorkerStatus.UNAVAILABLE,
                        NO_WORKER_EVENTS, dispatchMillis, null));
    }

    @Override
    public AttemptLogs selectLogs(UUID runId, UUID attemptId, int offset) {
        if (offset < 0) throw new RunException(400, "INVALID_ARGUMENT", "日志偏移量不得为负数");
        var workerId = assignedWorker(requireAttempt(runId, attemptId));
        if (workerId == null) return new AttemptLogs(runId, attemptId, null, WorkerStatus.NOT_ASSIGNED, List.of(), null);
        return worker.selectLogs(runId, attemptId, workerId, offset)
                .map(page -> new AttemptLogs(runId, attemptId, workerId, WorkerStatus.AVAILABLE,
                        page.entries(), page.nextOffset()))
                .orElseGet(() -> new AttemptLogs(runId, attemptId, workerId, WorkerStatus.UNAVAILABLE, List.of(), null));
    }

    private Attempt requireAttempt(UUID runId, UUID attemptId) {
        return runs.attempt(attemptId).filter(row -> runId.equals(row.runId()))
                .orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "该运行中未找到此 Attempt"));
    }

    private String assignedWorker(Attempt attempt) {
        return attempt.targetWorkerId() == null ? attempt.ownerWorkerId() : attempt.targetWorkerId();
    }
}
