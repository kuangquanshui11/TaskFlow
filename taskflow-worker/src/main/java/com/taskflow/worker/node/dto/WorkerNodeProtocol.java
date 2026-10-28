package com.taskflow.worker.node.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkerNodeProtocol {
    private WorkerNodeProtocol() { }
    public record Registration(String workerId,String displayName,UUID sessionId,List<String> handlerKeys,int parallelism,int maxPending,
            String dispatchTransport,String dispatchVhost,String dispatchExchange,String dispatchQueue,String dispatchRoutingKey) { }
    public record Heartbeat(String workerId,UUID sessionId,long generation,long sequence,int runningCount,int pendingCount) { }
    public record Acknowledgement(String workerId,UUID sessionId,long generation,long lastSequence,Instant expiresAt) { }
}
