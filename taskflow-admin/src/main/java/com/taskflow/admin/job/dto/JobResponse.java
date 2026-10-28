package com.taskflow.admin.job.dto;

import java.time.Instant;

import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.model.JobDefinition.MisfirePolicy;
import com.taskflow.admin.job.model.JobDefinition.ScheduleType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record JobResponse(long id, String name, String description, String handlerKey, JsonNode payload,
                          ScheduleType scheduleType, String cronExpression, String timeZone,
                          boolean enabled, int timeoutSeconds, int maxAttempts, int retryIntervalSeconds,
                          MisfirePolicy misfirePolicy, long configVersion, Instant createdAt, Instant updatedAt) {

    public static JobResponse from(JobDefinition job, JsonMapper json) {
        var configuration = job.configuration();
        return new JobResponse(job.id(), configuration.name(), configuration.description(),
                configuration.handlerKey(),
                configuration.payloadJson() == null ? null : json.readTree(configuration.payloadJson()),
                configuration.scheduleType(), configuration.cronExpression(), configuration.timeZone(),
                configuration.enabled(), configuration.timeoutSeconds(), configuration.maxAttempts(),
                configuration.retryIntervalSeconds(), configuration.misfirePolicy(), job.configVersion(),
                job.createdAt(), job.updatedAt());
    }
}
