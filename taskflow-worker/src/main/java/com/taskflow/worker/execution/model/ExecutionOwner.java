package com.taskflow.worker.execution.model;

import java.util.UUID;

public record ExecutionOwner(String workerId, UUID sessionId) { }
