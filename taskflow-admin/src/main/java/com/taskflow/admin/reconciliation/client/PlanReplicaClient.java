package com.taskflow.admin.reconciliation.client;

import java.util.*;
import java.util.stream.Collectors;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.Replica;
import com.taskflow.admin.remote.client.PlanSchedulerRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PlanReplicaClient {
    private final PlanSchedulerRemote remote;
    private final JobSyncProperties properties;
    private final JsonMapper json;

    public PlanReplicaClient(PlanSchedulerRemote remote, JobSyncProperties properties, JsonMapper json) {
        this.remote = remote; this.properties = properties; this.json = json;
    }

    public Map<Long, Replica> select(List<Long> ids, UUID requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Replica query inside transaction");
        if (properties.controlToken().isBlank()) throw failure("SERVICE_NOT_CONFIGURED");
        String response;
        try {
            response = remote.versions(ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    RemoteErrors.headers(properties.controlToken(), requestId.toString()));
        } catch (RuntimeException unavailable) { throw failure("SCHEDULER_UNAVAILABLE"); }
        try {
            var root = json.readTree(response);
            if (!root.isArray() || root.size() > ids.size()) throw new IllegalArgumentException();
            var result = new HashMap<Long, Replica>();
            for (var node : root) {
                if (!node.path("jobId").isIntegralNumber() || !node.path("configVersion").isIntegralNumber()
                        || !node.path("deleted").isBoolean() || !node.path("enabled").isBoolean()) throw new IllegalArgumentException();
                var replica = json.treeToValue(node, Replica.class);
                if (!ids.contains(replica.jobId()) || replica.configVersion() < 1 || (replica.deleted() && replica.enabled())
                        || result.put(replica.jobId(), replica) != null) throw new IllegalArgumentException();
            }
            return result;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | NullPointerException malformed) {
            throw failure("SCHEDULER_PROTOCOL_ERROR");
        }
    }
    private PlanQueryException failure(String code) { return new PlanQueryException(503, code, "调度副本暂时不可核实"); }
}
