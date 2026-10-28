package com.taskflow.worker.execution.model;

import java.time.Instant;
import java.util.UUID;

/** Handler 已结束的不可变结果；保存重试不得重新进入 Handler。 */
public record ExecutionCompletion(UUID attemptId, UUID runId, String state, String resultCode,
        String message, boolean retryable, Instant finishedAt) { }
