package com.taskflow.scheduler.execution.model;

import java.time.Instant;
import java.util.UUID;

public record RunCancellation(UUID runId, UUID attemptId, UUID requestKey, String reason, String actor,
                              Instant requestedAt, Instant deadlineAt, Outcome outcome, Instant finishedAt,
                              String workerResultStatus, String workerResultCode, Instant workerResultAt) {
    public enum Outcome { REQUESTED, BEFORE_START, STOP_CONFIRMED, STOP_UNCONFIRMED }
}
