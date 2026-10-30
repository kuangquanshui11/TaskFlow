package com.taskflow.scheduler.plan.dto;

public record PlanVersion(long jobId, long configVersion, boolean deleted, boolean enabled) { }
