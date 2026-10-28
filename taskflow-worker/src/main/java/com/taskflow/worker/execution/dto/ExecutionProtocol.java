package com.taskflow.worker.execution.dto;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public final class ExecutionProtocol {
    private ExecutionProtocol() { }
    public record Snapshot(
            @Positive long jobId, @Positive long configVersion, @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String handlerKey, JsonNode payload,
            @Min(1) @Max(86400) int timeoutSeconds, @Min(1) @Max(10) int maxAttempts,
            @Min(1) @Max(86400) int retryIntervalSeconds, @NotBlank String scheduleType,
            String cronExpression, @NotBlank String timeZone, @NotBlank String misfirePolicy,
            boolean enabled, @Size(max = 500) String description) { }
    public record Create(@NotNull UUID requestKey, @NotNull @Valid Snapshot snapshot) { }
    public record Dispatch(@NotNull UUID attemptId, @NotNull UUID runId, @Min(1) int attemptNumber,
                           @Positive long leaseVersion, @NotNull Instant expiresAt,
                           @NotNull @Valid Snapshot snapshot) { }
    public record Result(@NotNull UUID attemptId, @NotNull UUID runId, @Positive long leaseVersion,
                         @Pattern(regexp = "SUCCEEDED|FAILED") @NotBlank String status,
                         @NotBlank @Size(max = 64) String resultCode,
                         @NotNull @Size(max = 1024) String message, boolean retryable) { }
    public record Accepted(UUID attemptId, String state) { }
    public record LeaseRequest(@NotNull UUID attemptId, @NotNull UUID runId, @Positive long leaseVersion,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_.-]{0,99}") String workerId, @NotNull UUID sessionId) { }
    public record LeaseAcknowledgement(UUID attemptId, UUID runId, long leaseVersion, String workerId,
            UUID sessionId, Instant serverTime, Instant leaseUntil, Instant executionDeadline, Instant hardDeadline) { }
}
