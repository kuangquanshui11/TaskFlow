package com.taskflow.scheduler.plan.dto;

import java.util.UUID;
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
            @NotNull @Pattern(regexp = "SKIP|FIRE_ONCE") String misfirePolicy) { }
}
