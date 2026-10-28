package com.taskflow.scheduler.node.model;

import java.time.Instant;
import java.util.UUID;

public record WorkerNode(String workerId, String displayName, UUID sessionId, long generation,
        String registrationFingerprint, String handlerKeysJson, int parallelism, int maxPending,
        int runningCount, int pendingCount, String dispatchTransport, String dispatchVhost,
        String dispatchExchange, String dispatchQueue, String dispatchRoutingKey, long lastSequence,
        Instant registeredAt, Instant lastHeartbeatAt, Instant expiresAt) {
    public boolean online(Instant now) { return expiresAt.isAfter(now); }
}
