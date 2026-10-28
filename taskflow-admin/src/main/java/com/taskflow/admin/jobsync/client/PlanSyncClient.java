package com.taskflow.admin.jobsync.client;

import com.taskflow.admin.remote.client.PlanSchedulerRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import com.taskflow.admin.jobsync.config.JobSyncProperties;
import com.taskflow.admin.jobsync.model.JobSyncRecords.*;
import com.taskflow.admin.jobsync.model.PlanView;
import com.taskflow.admin.jobsync.exception.PlanQueryException;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PlanSyncClient {
    private final JobSyncProperties properties;
    private final JsonMapper json;
    private final PlanSchedulerRemote remote;

    public PlanSyncClient(JobSyncProperties properties, JsonMapper json,PlanSchedulerRemote remote) {
        this.properties = properties;
        this.json = json;
        this.remote=remote;
    }

    public Acknowledgement send(Event event) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Plan sync inside SQL transaction");
        if (properties.controlToken().isBlank()) throw new DeliveryException("SERVICE_NOT_CONFIGURED", false);
        String response;
        try {
            response=remote.update(event,RemoteErrors.headers(properties.controlToken(),event.eventId().toString()));
        } catch (RuntimeException failed) {
            int code=RemoteErrors.status(failed);
            throw new DeliveryException("SCHEDULER_HTTP_"+code,code>=500 || code==408 || code==429);
        }
        try {
            var ack = json.readValue(response, Acknowledgement.class);
            if (ack == null || !event.eventId().equals(ack.eventId()) || event.jobId() != ack.jobId()
                    || event.configVersion() != ack.configVersion() || ack.acceptedVersion() < event.configVersion()
                    || !java.util.Set.of("APPLIED", "DUPLICATE", "SUPERSEDED").contains(ack.result()))
                throw new DeliveryException("SCHEDULER_PROTOCOL_ERROR", true);
            return ack;
        } catch (tools.jackson.core.JacksonException | NullPointerException error) {
            throw new DeliveryException("SCHEDULER_PROTOCOL_ERROR", true);
        }
    }

    public PlanView selectPlan(long jobId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Plan query inside SQL transaction");
        if (properties.controlToken().isBlank())
            throw new PlanQueryException(503, "SERVICE_NOT_CONFIGURED", "调度服务尚未配置");
        String response;
        try {
            response=remote.select(jobId,RemoteErrors.headers(properties.controlToken(),java.util.UUID.randomUUID().toString()));
        } catch (RuntimeException failed) {
            if (RemoteErrors.status(failed)==404) throw new PlanQueryException(404,"PLAN_NOT_FOUND","调度服务尚未收到该任务的计划副本");
            throw new PlanQueryException(503,"SCHEDULER_UNAVAILABLE","调度计划暂时不可查询，请稍后刷新");
        }
        try {
            var data = json.readTree(response);
            String state = data.path("calculationState").asString("");
            if (data.path("jobId").asLong() != jobId || data.path("configVersion").asLong() < 1
                    || !data.path("enabled").isBoolean() || !data.path("deleted").isBoolean() || !data.path("automaticTriggerEnabled").isBoolean()
                    || !java.util.Set.of("PENDING", "READY", "DISABLED", "MANUAL", "DELETED", "NO_FUTURE_FIRE", "INVALID").contains(state)
                    || !data.path("snapshotJson").isString() || !data.has("nextFireTime") || !data.has("calculatedAt"))
                throw new IllegalArgumentException("Invalid plan response");
            var snapshot = json.readTree(data.get("snapshotJson").stringValue());
            String zone = snapshot.path("timeZone").asString("");
            java.time.ZoneId.of(zone);
            String type = snapshot.path("scheduleType").asString("");
            if (!java.util.Set.of("MANUAL", "CRON").contains(type)) throw new IllegalArgumentException("Invalid schedule type");
            Instant next = time(data, "nextFireTime");
            Instant calculated = time(data, "calculatedAt");
            if (("READY".equals(state) != (next != null)) || (!"PENDING".equals(state) && calculated == null))
                throw new IllegalArgumentException("Inconsistent plan calculation");
            return new PlanView(jobId, data.get("configVersion").longValue(), data.get("deleted").booleanValue(),
                    data.get("enabled").booleanValue(), type, snapshot.path("cronExpression").isNull() ? null : snapshot.path("cronExpression").asString(),
                    zone, state, next, calculated, Instant.parse(data.path("updatedAt").asString()), data.get("automaticTriggerEnabled").booleanValue());
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | java.time.DateTimeException | NullPointerException malformed) {
            throw new PlanQueryException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回的计划格式不合法");
        }
    }

    private Instant time(JsonNode data, String name) {
        return data.path(name).isNull() ? null : Instant.parse(data.path(name).asString());
    }

    public static class DeliveryException extends RuntimeException {
        private final boolean retryable;
        public DeliveryException(String code, boolean retryable) { super(code); this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }
}
