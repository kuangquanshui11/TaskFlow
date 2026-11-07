package com.taskflow.worker.execution.model;

import java.time.Instant;
import java.util.UUID;

/** 本次执行的不可变结果；保存重试不得重新进入 Handler。 */
public record ExecutionCompletion(UUID attemptId, UUID runId, String state, String resultCode,
        String message, boolean retryable, boolean handlerInvoked, Instant finishedAt) { }
