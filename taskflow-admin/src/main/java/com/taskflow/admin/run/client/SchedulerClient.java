package com.taskflow.admin.run.client;

import com.taskflow.admin.remote.client.RunSchedulerRemote;
import com.taskflow.admin.remote.model.RemoteErrors;
import com.taskflow.admin.run.config.RunClientProperties;
import com.taskflow.admin.run.exception.RunException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SchedulerClient {
    private final RunClientProperties properties;
    private final JsonMapper json;
    private final RunSchedulerRemote remote;
    public SchedulerClient(RunClientProperties properties, JsonMapper json,RunSchedulerRemote remote) {
        this.properties = properties; this.json = json;
        this.remote=remote;
    }
    public JsonNode get(String path) { return call("GET", path, null); }
    public JsonNode post(String path, Object body) { return call("POST", path, body); }
    private JsonNode call(String method, String path, Object body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Scheduler call inside SQL transaction");
        if (properties.controlToken().isBlank())
            throw new RunException(503, "SERVICE_NOT_CONFIGURED", "手动执行服务尚未配置");
        var params = RemoteErrors.query(path);
        String response;
        try {
            var headers=RemoteErrors.headers(properties.controlToken(),java.util.UUID.randomUUID().toString());
            response=body==null?remote.get(RemoteErrors.action(path),params,headers):remote.insert(body,headers);
        } catch (RuntimeException failure) {
            int status=RemoteErrors.status(failure);
            if (status == 404 && path.startsWith("/internal/v1/runs/selectAttemptEvents?"))
                throw new RunException(404, "ATTEMPT_NOT_FOUND", "该运行中未找到此 Attempt");
            if (status==404) throw new RunException(404,"RUN_NOT_FOUND","运行记录尚未找到");
            if (status==409) throw new RunException(409,"IDEMPOTENCY_CONFLICT","原请求标识对应不同配置，请确认运行历史");
            throw new RunException(503,"SCHEDULER_UNAVAILABLE","调度服务暂时不可用，请使用相同请求标识确认状态");
        }
        try {
            var data = json.readTree(response);
            if (path.startsWith("/internal/v1/runs/selectAttemptEvents?")) {
                validateAttemptEvents(data, params);
                return data;
            }
            if (path.equals("/internal/v1/runs/selectSummary") || path.startsWith("/internal/v1/runs/selectSummary?")) {
                validateSummary(data);
                return data;
            }
            if (path.startsWith("/internal/v1/runs/selectGlobal?")) {
                validateGlobalPage(data, Integer.parseInt(params.get("page")), Integer.parseInt(params.get("pageSize")));
                return data;
            }
            if (data==null || !data.isObject() || (path.startsWith("/internal/v1/runs/select?")
                    ? !data.has("records") || !data.has("total") : !data.hasNonNull("id") || !data.hasNonNull("state")))
                throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回格式不合法");
            return data;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) {
            throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回格式不合法");
        }
    }

    private static void validateAttemptEvents(JsonNode data, java.util.Map<String, String> query) {
        var workerStatus = data == null ? "" : data.path("workerStatus").asString("");
        if (data == null || !data.isObject()
                || !query.get("runId").equals(data.path("runId").asString())
                || !query.get("attemptId").equals(data.path("attemptId").asString())
                || !validEventBatch(data.path("scheduler"))
                || !validEventBatch(data.path("worker"))
                || !java.util.Set.of("AVAILABLE", "UNAVAILABLE", "NOT_ASSIGNED").contains(workerStatus)
                || ("NOT_ASSIGNED".equals(workerStatus) ? !data.path("workerId").isNull() : !data.path("workerId").isString())
                || (!"AVAILABLE".equals(workerStatus)
                    && (data.path("worker").path("events").size() != 0 || data.path("worker").path("truncated").asBoolean())))
            throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回的 Attempt 事件格式不合法");
    }

    private static boolean validEventBatch(JsonNode batch) {
        var events = batch.path("events");
        if (!events.isArray() || events.size() > 100 || !batch.path("truncated").isBoolean()) return false;
        for (var event : events) {
            if (!event.path("id").isIntegralNumber() || !event.path("id").canConvertToLong()
                    || event.path("id").longValue() <= 0 || !event.path("eventType").isString()
                    || !event.path("occurredAt").isString()) return false;
            try {
                java.time.Instant.parse(event.path("occurredAt").asString());
            } catch (java.time.format.DateTimeParseException invalid) {
                return false;
            }
        }
        return true;
    }

    private static void validateGlobalPage(JsonNode data, int requestedPage, int requestedPageSize) {
        if (data == null || !data.isObject() || !data.path("records").isArray()
                || !data.path("total").isIntegralNumber() || !data.path("total").canConvertToLong()
                || data.path("total").longValue() < data.path("records").size()
                || !matchesPageParameter(data.path("page"), requestedPage)
                || !matchesPageParameter(data.path("pageSize"), requestedPageSize)
                || data.path("records").size() > requestedPageSize)
            throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回的分页数据不合法");
    }

    private static boolean matchesPageParameter(JsonNode value, int expected) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected;
    }

    public static void validateSummary(JsonNode data) {
        if (data == null || !data.isObject() || !data.path("total").isIntegralNumber()
                || !data.path("stateCounts").isObject() || !data.path("latency").isObject() || !data.path("failures").isArray())
            throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "调度服务返回格式不合法");
    }
}
