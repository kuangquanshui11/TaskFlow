package com.taskflow.admin.job.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

public record JobDefinition(long id, Configuration configuration, long configVersion,
                            Instant createdAt, Instant updatedAt) {

    public enum ScheduleType { MANUAL, CRON }
    public enum MisfirePolicy { SKIP, FIRE_ONCE }
    public enum RetryPolicy { FIXED, EXPONENTIAL }
    public enum OverlapPolicy { ALLOW_OVERLAP, SERIAL }

    public record Configuration(String name, String description, String handlerKey, String payloadJson,
                                ScheduleType scheduleType, String cronExpression, String timeZone,
                                boolean enabled, int timeoutSeconds, int maxAttempts,
                                int retryIntervalSeconds, MisfirePolicy misfirePolicy,
                                @JsonInclude(JsonInclude.Include.NON_NULL) RetryPolicy retryPolicy,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryMaxIntervalSeconds,
                                @JsonInclude(JsonInclude.Include.NON_NULL) OverlapPolicy overlapPolicy) {
        public Configuration {
            // 固定间隔保留既有快照的 JSON 形状，历史配置事件可用原指纹重放。
            if (retryPolicy == RetryPolicy.FIXED) retryPolicy = null;
            if (overlapPolicy == OverlapPolicy.ALLOW_OVERLAP) overlapPolicy = null;
        }

        public Configuration(String name, String description, String handlerKey, String payloadJson,
                             ScheduleType scheduleType, String cronExpression, String timeZone,
                             boolean enabled, int timeoutSeconds, int maxAttempts,
                             int retryIntervalSeconds, MisfirePolicy misfirePolicy) {
            this(name, description, handlerKey, payloadJson, scheduleType, cronExpression, timeZone,
                    enabled, timeoutSeconds, maxAttempts, retryIntervalSeconds, misfirePolicy, null, null, null);
        }
    }
}
