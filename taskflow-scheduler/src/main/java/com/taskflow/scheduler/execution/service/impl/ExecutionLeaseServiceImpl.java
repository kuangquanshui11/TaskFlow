package com.taskflow.scheduler.execution.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.taskflow.scheduler.execution.config.ExecutionLeaseProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.service.IExecutionLeaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExecutionLeaseServiceImpl implements IExecutionLeaseService {
    private final RunDataMapper mapper;
    private final ExecutionLeaseProperties leases;
    private final JsonMapper json;

    public ExecutionLeaseServiceImpl(RunDataMapper mapper, ExecutionLeaseProperties leases,
                                     JsonMapper json) {
        this.mapper = mapper; this.leases = leases; this.json = json;
    }

    @Override @Transactional
    public LeaseAcknowledgement updateLease(LeaseRequest request) {
        // 与结果提交、超时扫描统一按 Run → Attempt 加锁；首次读取仅定位不可变 runId。
        var initial = mapper.attempt(request.attemptId()).orElseThrow(this::stale);
        var run = mapper.lock(initial.runId()).orElseThrow(this::stale);
        var attempt = mapper.lockAttempt(request.attemptId()).orElseThrow(this::stale);
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!request.runId().equals(run.id()) || run.currentAttempt() != attempt.attemptNumber()
                || !java.util.Set.of("READY", "RUNNING", "RETRY_WAIT").contains(run.state())
                || attempt.leaseVersion() != request.leaseVersion()
                || !request.workerId().equals(attempt.targetWorkerId())
                || !java.util.Set.of("READY", "DISPATCHING", "RUNNING").contains(attempt.state())
                || attempt.expiresAt() == null || attempt.startedAt() == null || !attempt.validAt(now)
                || attempt.ownerWorkerId() != null && (!attempt.ownerWorkerId().equals(request.workerId())
                    || !attempt.ownerSessionId().equals(request.sessionId()))) throw stale();
        // 截止时间只依赖首次派发时间和不可变快照，不受重启后的宽限配置变化影响。
        var snapshot = json.readValue(run.snapshotJson(), Snapshot.class);
        var executionDeadline = attempt.startedAt().plusSeconds(snapshot.timeoutSeconds());
        if (executionDeadline.isAfter(attempt.expiresAt())) executionDeadline = attempt.expiresAt();
        // 首次领取必须还有执行预算；已有执行者可在宽限期内续租以保存和上报结果。
        if (attempt.ownerWorkerId() == null && !executionDeadline.isAfter(now)) throw stale();
        var until = now.plus(leases.duration());
        if (until.isAfter(attempt.expiresAt())) until = attempt.expiresAt();
        if (!mapper.updateLease(attempt, request.workerId(), request.sessionId(), until, now)) throw stale();
        if (!"RUNNING".equals(attempt.state())) {
            if (!mapper.accepted(attempt, now)
                    || !mapper.runState(run, "RUNNING", attempt.attemptNumber(), null, null, now)) throw stale();
        }
        return new LeaseAcknowledgement(request.attemptId(), request.runId(), request.leaseVersion(),
                request.workerId(), request.sessionId(), now, until, executionDeadline, attempt.expiresAt());
    }

    private RunException stale() { return new RunException(409, "STALE_LEASE", "执行租约无效或执行者不匹配"); }
}
