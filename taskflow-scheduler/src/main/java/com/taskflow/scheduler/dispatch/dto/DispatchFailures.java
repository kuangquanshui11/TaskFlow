package com.taskflow.scheduler.dispatch.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DispatchFailures {
    private DispatchFailures() { }

    public enum Source { DEAD_LETTER, OUTBOX, RESULT_REPORT }
    public enum Action { ACKNOWLEDGE, RESOLVE }

    public record DeadLetter(String id, UUID runId, UUID attemptId, UUID eventId, String queue,
                             String sourceQueue, String reason, String payloadHash, Instant receivedAt) { }

    public record Failure(Source source, String id, UUID runId, UUID attemptId, UUID eventId, String reason,
                          Integer attempts, String queue, String sourceQueue, Instant observedAt, List<Disposition> history) { }

    public record View(UUID runId, UUID attemptId, UUID eventId, String attemptState, Long leaseVersion,
                       Instant leaseUntil, String workerStatus, boolean truncated, List<Failure> records) { }

    public record DispositionRequest(@NotNull UUID requestKey, @NotNull Source source,
            @NotBlank @Pattern(regexp="[a-f0-9-]{36,64}") String id, @NotNull Action action,
            @NotBlank @Size(max=255) String actor, @NotBlank @Size(max=250) String conclusion,
            @NotBlank @Size(max=250) String evidence) { }

    public record Disposition(UUID requestKey, Source source, String id, Action action, String actor,
                              String conclusion, String evidence, Instant recordedAt) { }
}
