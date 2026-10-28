package com.taskflow.admin.job.dto;

import java.time.Instant;

import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public final class JobRequests {
    private JobRequests() {
    }

    public record Write(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String handlerKey,
            JsonNode payload,
            ScheduleType scheduleType,
            @Size(max = 100) String cronExpression,
            @NotBlank @Size(max = 64) String timeZone,
            Boolean enabled,
            @Min(1) @Max(86400) Integer timeoutSeconds,
            @Min(1) @Max(10) Integer maxAttempts,
            @Min(1) @Max(86400) Integer retryIntervalSeconds,
            MisfirePolicy misfirePolicy) {

        public Write {
            name = name == null ? null : name.strip();
            handlerKey = handlerKey == null ? null : handlerKey.strip();
            scheduleType = scheduleType == null ? ScheduleType.MANUAL : scheduleType;
            cronExpression = cronExpression == null || cronExpression.isBlank() ? null : cronExpression.strip();
            timeZone = timeZone == null ? "Asia/Shanghai" : timeZone.strip();
            enabled = enabled == null ? false : enabled;
            timeoutSeconds = timeoutSeconds == null ? 30 : timeoutSeconds;
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            retryIntervalSeconds = retryIntervalSeconds == null ? 5 : retryIntervalSeconds;
            misfirePolicy = misfirePolicy == null ? MisfirePolicy.SKIP : misfirePolicy;
        }

        public Configuration toConfiguration() {
            if (payload != null && !payload.isNull() && !payload.isObject()) {
                throw JobException.invalid("payload 必须是 JSON 对象或 null");
            }
            return new Configuration(name, description, handlerKey,
                    payload == null || payload.isNull() ? null : payload.toString(),
                    scheduleType, cronExpression, timeZone, enabled, timeoutSeconds,
                    maxAttempts, retryIntervalSeconds, misfirePolicy);
        }
    }

    public record Update(@NotNull @Positive Long id, @NotNull @Positive Long configVersion,
                         @NotNull @Valid Write job) {
    }

    public record Mutation(@NotNull @Positive Long id, @NotNull @Positive Long configVersion) {
    }

    public record Select(@Min(1) @Max(1000000) Integer page,
                         @Min(1) @Max(100) Integer pageSize,
                         @Size(max = 100) String keyword,
                         Boolean enabled, @Size(max = 100) String handlerKey) {
        public Select {
            page = page == null ? 1 : page;
            pageSize = pageSize == null ? 20 : pageSize;
        }
    }

    public record Preview(@NotBlank @Size(max = 100) String cronExpression,
                          @NotBlank @Size(max = 64) String timeZone,
                          Instant from, @Min(1) @Max(10) Integer count) {
        public Preview {
            timeZone = timeZone == null ? "Asia/Shanghai" : timeZone;
            count = count == null ? 5 : count;
        }
    }
}
