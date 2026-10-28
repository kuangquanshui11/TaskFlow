package com.taskflow.admin.jobsync.model;

import java.time.Instant;

/** 调度服务实际保存的版本；不从 Admin 配置或缓存推测下一计划时间。 */
public record PlanView(long jobId, long configVersion, boolean deleted, boolean enabled,
                       String scheduleType, String cronExpression, String timeZone,
                       String calculationState, Instant nextFireTime, Instant calculatedAt, Instant updatedAt,
                       boolean automaticTriggerEnabled) { }
