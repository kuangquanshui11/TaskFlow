package com.taskflow.worker.execution.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionCancelledException;

/** 本地资格检查不能与远程副作用原子提交；下游仍需业务幂等或资源范围内的 fencing。 */
public final class ExecutionContext {
    private final Dispatch dispatch;
    private final LeaseRequest owner;
    private final Duration safetyMargin;
    private final Instant executionDeadline;
    private final long executionDeadlineNanos;
    private long leaseDeadlineNanos;
    private String cancelled;
    private boolean handlerFinished;
    private boolean closed;

    public ExecutionContext(Dispatch dispatch, LeaseRequest owner, LeaseAcknowledgement ack,
                            Duration safetyMargin, long requestStartedNanos) {
        this.dispatch = dispatch; this.owner = owner; this.safetyMargin = safetyMargin;
        validate(ack);
        executionDeadline = ack.executionDeadline();
        executionDeadlineNanos = requestStartedNanos + Math.min(
                Duration.between(ack.serverTime(), ack.executionDeadline()).toNanos(),
                Duration.ofSeconds(dispatch.snapshot().timeoutSeconds()).toNanos());
        leaseDeadlineNanos = safeDeadline(ack, requestStartedNanos);
        checkActive();
    }

    public UUID runId() { return dispatch.runId(); }
    public UUID attemptId() { return dispatch.attemptId(); }
    public int attemptNumber() { return dispatch.attemptNumber(); }
    public long leaseVersion() { return dispatch.leaseVersion(); }
    public String workerId() { return owner.workerId(); }
    public Instant executionDeadline() { return executionDeadline; }
    public Instant hardDeadline() { return dispatch.expiresAt(); }
    public String businessIdempotencyKey(String operation) {
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("操作标识不能为空");
        return runId() + ":" + operation;
    }

    public synchronized void checkActive() {
        if (cancelled()) throw new ExecutionCancelledException(cancelled);
        if (closed || handlerFinished) throw new ExecutionCancelledException("EXECUTION_CLOSED");
    }
    public synchronized boolean cancelled() {
        if (cancelled == null && !closed) {
            long now = System.nanoTime();
            if (!handlerFinished && now - executionDeadlineNanos >= 0) cancelled = "EXECUTION_TIMEOUT";
            else if (now - leaseDeadlineNanos >= 0) cancelled = "LEASE_LOST";
        }
        return cancelled != null;
    }
    public synchronized String cancellationCode() { cancelled(); return cancelled; }
    public synchronized void cancel(String reason) { if (cancelled == null && !closed) cancelled = reason; }
    public synchronized void handlerFinished() { handlerFinished = true; }
    public synchronized void close() { closed = true; }
    public synchronized boolean renewable() { return !closed && !cancelled(); }
    public synchronized void renewed(LeaseAcknowledgement ack, long requestStartedNanos) {
        // 迟到的确认不能让本地已经失效的执行资格复活。
        if (!renewable()) return;
        validate(ack);
        if (!executionDeadline.equals(ack.executionDeadline())) throw new IllegalArgumentException("执行截止时间发生变化");
        leaseDeadlineNanos = safeDeadline(ack, requestStartedNanos);
        cancelled();
    }
    private long safeDeadline(LeaseAcknowledgement ack, long started) {
        return started + Duration.between(ack.serverTime(), ack.leaseUntil()).minus(safetyMargin).toNanos();
    }
    private void validate(LeaseAcknowledgement ack) {
        if (ack == null || !owner.attemptId().equals(ack.attemptId()) || !owner.runId().equals(ack.runId())
                || owner.leaseVersion() != ack.leaseVersion() || !owner.workerId().equals(ack.workerId())
                || !owner.sessionId().equals(ack.sessionId()) || ack.serverTime() == null || ack.leaseUntil() == null
                || ack.executionDeadline() == null || !dispatch.expiresAt().equals(ack.hardDeadline())
                || ack.leaseUntil().isAfter(ack.hardDeadline()) || ack.executionDeadline().isAfter(ack.hardDeadline())
                || !ack.leaseUntil().isAfter(ack.serverTime()))
            throw new IllegalArgumentException("Scheduler 续租确认不合法");
    }
}
