package com.taskflow.admin.reconciliation.client;

import java.util.*;
import java.util.stream.Collectors;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.Replica;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.ReplicaPage;
import com.taskflow.admin.remote.client.PlanSchedulerRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@Component
public class PlanReplicaClient {
    private final PlanSchedulerRemote remote;
    private final JobSyncProperties properties;
    private final JsonMapper json;

    public PlanReplicaClient(PlanSchedulerRemote remote, JobSyncProperties properties, JsonMapper json) {
        this.remote = remote; this.properties = properties; this.json = json;
    }

    public Map<Long, Replica> select(List<Long> ids, UUID requestId) {
        var headers = headers(requestId);
        String response;
        try {
            response = remote.versions(ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    headers);
        } catch (RuntimeException unavailable) { throw failure("SCHEDULER_UNAVAILABLE"); }
        try {
            var root = json.readTree(response);
            if (!root.isArray() || root.size() > ids.size()) throw new IllegalArgumentException();
            var result = new HashMap<Long, Replica>();
            for (var node : root) {
                var replica = replica(node);
                if (!ids.contains(replica.jobId()) || result.put(replica.jobId(), replica) != null)
                    throw new IllegalArgumentException();
            }
            return result;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | NullPointerException malformed) {
            throw failure("SCHEDULER_PROTOCOL_ERROR");
        }
    }

    public ReplicaPage page(long afterJobId, Long upperJobId, int batchSize, UUID requestId) {
        var headers = headers(requestId);
        String response;
        try { response = remote.replicaPage(afterJobId, upperJobId, batchSize, headers); }
        catch (RuntimeException unavailable) { throw failure("SCHEDULER_UNAVAILABLE"); }
        try {
            var root = json.readTree(response);
            var entries = root.path("replicas");
            long upper = number(root.path("upperJobId"));
            if (upper < afterJobId || (upperJobId != null && upper != upperJobId)
                    || !entries.isArray() || entries.size() > batchSize || !root.path("hasMore").isBoolean())
                throw new IllegalArgumentException();
            boolean more = root.path("hasMore").asBoolean();
            var replicas = new ArrayList<Replica>();
            long previous = afterJobId;
            for (var entry : entries) {
                var replica = replica(entry);
                if (replica.jobId() <= previous || replica.jobId() > upper || replica.fingerprint() == null)
                    throw new IllegalArgumentException();
                replicas.add(replica);
                previous = replica.jobId();
            }
            if (more && (replicas.size() != batchSize || previous >= upper)) throw new IllegalArgumentException();
            return new ReplicaPage(upper, more, replicas);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | NullPointerException malformed) {
            throw failure("SCHEDULER_PROTOCOL_ERROR");
        }
    }

    private Replica replica(JsonNode node) {
        if (number(node.path("jobId")) < 1 || number(node.path("configVersion")) < 1
                || !node.path("deleted").isBoolean() || !node.path("enabled").isBoolean())
            throw new IllegalArgumentException();
        var fingerprint = node.path("fingerprint");
        if (!fingerprint.isMissingNode() && !fingerprint.isNull()
                && (!fingerprint.isString() || !fingerprint.asString().matches("INVALID|[0-9a-f]{64}")))
            throw new IllegalArgumentException();
        return json.treeToValue(node, Replica.class);
    }

    private long number(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException();
        return node.asLong();
    }

    private Map<String, String> headers(UUID requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Replica query inside transaction");
        if (properties.controlToken().isBlank()) throw failure("SERVICE_NOT_CONFIGURED");
        return RemoteErrors.headers(properties.controlToken(), requestId.toString());
    }
    private PlanQueryException failure(String code) { return new PlanQueryException(503, code, "调度副本暂时不可核实"); }
}
