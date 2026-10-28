package com.taskflow.admin.job.model;

import java.time.Instant;

public record JobDefinition(long id, Configuration configuration, long configVersion,
                            Instant createdAt, Instant updatedAt) {

    public enum ScheduleType { MANUAL, CRON }
    public enum MisfirePolicy { SKIP, FIRE_ONCE }

    public record Configuration(String name, String description, String handlerKey, String payloadJson,
                                ScheduleType scheduleType, String cronExpression, String timeZone,
                                boolean enabled, int timeoutSeconds, int maxAttempts,
                                int retryIntervalSeconds, MisfirePolicy misfirePolicy) {
    }
}
