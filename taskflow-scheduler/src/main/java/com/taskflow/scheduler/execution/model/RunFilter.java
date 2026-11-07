package com.taskflow.scheduler.execution.model;

import java.time.Instant;
import java.util.UUID;

public record RunFilter(Instant from, Instant to, Long jobId, String state, String triggerType, UUID runId, String resultCode) { }
