package com.taskflow.admin.node.dto;

import java.time.Instant;
import java.util.List;

public final class WorkerNodeResponse {
    private WorkerNodeResponse() { }
    public record View(String workerId,String displayName,long generation,List<String> handlerKeys,
            int parallelism,int maxPending,int runningCount,int pendingCount,String dispatchTransport,
            String dispatchVhost,String dispatchExchange,String dispatchQueue,String dispatchRoutingKey,
            String status,boolean target,boolean dispatchEligible,Instant registeredAt,Instant lastHeartbeatAt,Instant expiresAt) { }
    public record Summary(long total,long online,long offline) { }
    public record Page(List<View> records,long total,int page,int pageSize,Summary summary,Instant observedAt) { }
}
