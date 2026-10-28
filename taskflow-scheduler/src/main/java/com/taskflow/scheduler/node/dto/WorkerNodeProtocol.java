package com.taskflow.scheduler.node.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;

public final class WorkerNodeProtocol {
    private WorkerNodeProtocol() { }
    public record Registration(
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9_.-]{0,99}") String workerId,
            @NotBlank @Size(max=100) String displayName, @NotNull UUID sessionId,
            @NotEmpty @Size(max=100) List<@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_.-]{0,99}") String> handlerKeys,
            @Min(1) @Max(64) int parallelism, @Min(1) @Max(10000) int maxPending,
            @NotNull @Pattern(regexp="http|rabbitmq") String dispatchTransport,
            @Size(max=100) String dispatchVhost, @Size(max=200) String dispatchExchange,
            @Size(max=200) String dispatchQueue, @Size(max=200) String dispatchRoutingKey) { }
    public record Heartbeat(
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9_.-]{0,99}") String workerId,
            @NotNull UUID sessionId, @Positive long generation, @Positive long sequence,
            @Min(0) int runningCount, @Min(0) int pendingCount) { }
    public record Acknowledgement(String workerId, UUID sessionId, long generation, long lastSequence, Instant expiresAt) { }
    public record View(String workerId, String displayName, long generation, List<String> handlerKeys,
            int parallelism, int maxPending, int runningCount, int pendingCount,
            String dispatchTransport, String dispatchVhost, String dispatchExchange, String dispatchQueue, String dispatchRoutingKey,
            String status, boolean target, boolean dispatchEligible, Instant registeredAt, Instant lastHeartbeatAt, Instant expiresAt) { }
    public record Summary(long total, long online, long offline) { }
    public record Page(List<View> records, long total, int page, int pageSize, Summary summary, Instant observedAt) { }
}
