package com.taskflow.worker.execution.model;

import java.time.Instant;
import java.util.UUID;

public record ExecutionRow(UUID attemptId, UUID runId, String fingerprint, String dispatchJson,
        String state, String resultCode, String message, boolean retryable, boolean reported,
        int reportCount, Instant nextReportAt, Instant createdAt, Instant finishedAt,
        String reportWorkerId, UUID reportSessionId) { }
