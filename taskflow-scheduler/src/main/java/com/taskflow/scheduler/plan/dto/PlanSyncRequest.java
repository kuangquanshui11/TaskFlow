package com.taskflow.scheduler.plan.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.taskflow.scheduler.execution.model.RetryDelay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PlanSyncRequest(@NotNull UUID eventId, @Positive long jobId, @Positive long configVersion,
                              boolean deleted, @NotNull @Valid Configuration configuration) {
    public record Configuration(
            @NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String handlerKey,
            @Size(max = 65536) String payloadJson,
            @NotNull @Pattern(regexp = "MANUAL|CRON") String scheduleType,
            @Size(max = 100) String cronExpression, @NotBlank @Size(max = 64) String timeZone,
            boolean enabled, @Min(1) @Max(86400) int timeoutSeconds,
            @Min(1) @Max(10) int maxAttempts, @Min(1) @Max(86400) int retryIntervalSeconds,
            @NotNull @Pattern(regexp = "SKIP|FIRE_ONCE") String misfirePolicy,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "FIXED|EXPONENTIAL") String retryPolicy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryMaxIntervalSeconds) {
        public Configuration {
            if ("FIXED".equals(retryPolicy)) retryPolicy = null;
        }

        public Configuration(String name, String description, String handlerKey, String payloadJson,
                             String scheduleType, String cronExpression, String timeZone, boolean enabled,
                             int timeoutSeconds, int maxAttempts, int retryIntervalSeconds, String misfirePolicy) {
            this(name, description, handlerKey, payloadJson, scheduleType, cronExpression, timeZone, enabled,
                    timeoutSeconds, maxAttempts, retryIntervalSeconds, misfirePolicy, null, null);
        }

        @AssertTrue(message = "重试间隔上限与策略不匹配")
        @JsonIgnore
        public boolean isRetryPolicyValid() {
            return RetryDelay.valid(retryPolicy, retryIntervalSeconds, retryMaxIntervalSeconds);
        }
    }
}
