package com.taskflow.admin.run.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import com.taskflow.admin.audit.service.IOperationAuditService;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.job.model.JobDefinition;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import com.taskflow.admin.run.dto.RunRequests.Rerun;
import com.taskflow.admin.run.dto.RunRequests.RerunConfiguration;
import com.taskflow.admin.run.dto.RunRequests.ReviewOutcome;
import com.taskflow.admin.run.exception.RunException;
import com.taskflow.admin.run.service.IRunService;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RunServiceImpl implements IRunService {
    private static final Instant EARLIEST_QUERY_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LATEST_QUERY_TIME = Instant.parse("9999-12-31T23:59:59.999Z");
    private static final Duration MAX_QUERY_WINDOW = Duration.ofDays(7);

    private final IJobService jobs;
    private final SchedulerClient scheduler;
    private final JsonMapper json;
    private final IOperationAuditService audit;
    public RunServiceImpl(IJobService jobs, SchedulerClient scheduler, JsonMapper json, IOperationAuditService audit) {
        this.jobs = jobs; this.scheduler = scheduler; this.json = json;
        this.audit = audit;
    }
    @Override
    public JsonNode insert(Insert request) {
        var operation = audit.beginManual(request.requestKey(), request.jobId(), request.configVersion());
        JsonNode result;
        try {
            var previous = priorSubmission(request.requestKey());
            if (previous != null) {
                if (previous.hasNonNull("sourceRunId") || previous.path("jobId").longValue() != request.jobId()
                        || previous.path("configVersion").longValue() != request.configVersion()
                        || previous.hasNonNull("triggerActor") && !operation.actor().equals(previous.path("triggerActor").asString()))
                    throw idempotencyConflict();
                result = previous;
            } else {
                var snapshot = snapshot(jobs.selectById(request.jobId()), request.configVersion());
                result = scheduler.post("/internal/v1/runs/insert", Map.of("requestKey", request.requestKey(),
                        "snapshot", snapshot, "actor", operation.actor()));
            }
        } catch (JobException rejected) {
            audit.rejectManual(operation, rejected.code());
            throw rejected;
        } catch (RunException resultError) {
            if (resultError.status() < 500) audit.rejectManual(operation, resultError.code());
            throw resultError;
        }
        audit.observeRun(operation, result);
        return publicRun(result);
    }

    @Override
    public RerunConfiguration selectRerunConfiguration(UUID sourceRunId) {
        var job = rerunJob(sourceRunId);
        var config = job.configuration();
        return new RerunConfiguration(config.name(), config.handlerKey(), Long.toString(job.configVersion()), config.enabled());
    }

    @Override
    public JsonNode insertRerun(Rerun request) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        var previous = priorSubmission(request.requestKey());
        if (previous != null) {
            if (!request.sourceRunId().toString().equals(previous.path("sourceRunId").asString())
                    || !actor.equals(previous.path("rerunActor").asString())
                    || previous.path("configVersion").longValue() != request.configVersion()) throw idempotencyConflict();
            return previous;
        }
        var snapshot = snapshot(rerunJob(request.sourceRunId()), request.configVersion());
        return scheduler.post("/internal/v1/runs/insertRerun", Map.of("requestKey", request.requestKey(),
                "sourceRunId", request.sourceRunId(), "actor", actor, "snapshot", snapshot));
    }

    private JsonNode priorSubmission(UUID requestKey) {
        try {
            return scheduler.get("/internal/v1/runs/selectByRequestKey?requestKey=" + requestKey);
        } catch (RunException missing) {
            if (missing.status() != 404) throw missing;
            return null;
        }
    }

    private JobDefinition rerunJob(UUID sourceRunId) {
        var source = selectById(sourceRunId);
        if (!source.path("rerunEligible").isBoolean() || !source.path("rerunEligible").booleanValue())
            throw new RunException(422, "RUN_RERUN_UNSAFE", "无法确认原运行的所有 Attempt 均未启动 Handler");
        var jobId = source.path("jobId");
        if (!jobId.isIntegralNumber() || !jobId.canConvertToLong() || jobId.longValue() < 1)
            throw new RunException(503, "SCHEDULER_PROTOCOL_ERROR", "原运行的任务 ID 不合法");
        return jobs.selectById(jobId.longValue());
    }

    private static RunException idempotencyConflict() {
        return new RunException(409, "IDEMPOTENCY_CONFLICT", "该请求标识已用于其他运行、操作者或配置版本");
    }

    @Override
    public JsonNode updateOutcomeReview(ReviewOutcome request) {
        var actor = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        return scheduler.post("/internal/v1/runs/updateOutcomeReview", Map.of("requestKey", request.requestKey(),
                "runId", request.runId(), "attemptId", request.attemptId(), "decision", request.decision(),
                "evidence", request.evidence(), "actor", actor));
    }

    private ObjectNode snapshot(JobDefinition job, long configVersion) {
        // 权威配置由数据库读取。本地只读事务在调用 Scheduler 前已结束。
        if (job.configVersion() != configVersion)
            throw new RunException(409, "JOB_VERSION_CONFLICT", "任务配置已变更，请刷新任务后再执行");
        var configuration = job.configuration();
        var snapshot = json.createObjectNode();
        snapshot.put("jobId", job.id()); snapshot.put("configVersion", job.configVersion());
        snapshot.put("name", configuration.name()); snapshot.put("handlerKey", configuration.handlerKey());
        snapshot.set("payload", configuration.payloadJson() == null ? json.nullNode() : json.readTree(configuration.payloadJson()));
        snapshot.put("timeoutSeconds", configuration.timeoutSeconds()); snapshot.put("maxAttempts", configuration.maxAttempts());
        snapshot.put("retryIntervalSeconds", configuration.retryIntervalSeconds()); snapshot.put("scheduleType", configuration.scheduleType().name());
        snapshot.put("cronExpression", configuration.cronExpression()); snapshot.put("timeZone", configuration.timeZone());
        snapshot.put("misfirePolicy", configuration.misfirePolicy().name()); snapshot.put("enabled", configuration.enabled());
        snapshot.put("description", configuration.description());
        if (configuration.retryPolicy() != null) {
            snapshot.put("retryPolicy", configuration.retryPolicy().name());
            snapshot.put("retryMaxIntervalSeconds", configuration.retryMaxIntervalSeconds());
        }
        if (configuration.overlapPolicy() != null) snapshot.put("overlapPolicy", configuration.overlapPolicy().name());
        if (Boolean.TRUE.equals(configuration.pauseOnUnknownOutcome())) snapshot.put("pauseOnUnknownOutcome", true);
        return snapshot;
    }
    @Override
    public JsonNode select(long jobId, int page, int pageSize) {
        if (jobId < 1 || page < 1 || page > 1000000 || pageSize < 1 || pageSize > 100)
            throw new RunException(400, "INVALID_ARGUMENT", "运行分页参数不合法");
        return scheduler.get("/internal/v1/runs/select?jobId=" + jobId + "&page=" + page + "&pageSize=" + pageSize);
    }
    @Override public JsonNode selectById(UUID id) { return publicRun(scheduler.get("/internal/v1/runs/selectById?id=" + id)); }
    @Override
    public JsonNode selectAttemptEvents(UUID runId, UUID attemptId) {
        return scheduler.get("/internal/v1/runs/selectAttemptEvents?runId=" + runId + "&attemptId=" + attemptId);
    }
    @Override
    public JsonNode selectGlobal(com.taskflow.admin.run.dto.RunQuery query) {
        if (query.page() < 1 || query.page() > 10000 || query.pageSize() < 1 || query.pageSize() > 100
                || (query.jobId() != null && query.jobId() < 1)
                || (query.timeField() != null && !java.util.Set.of("CREATED", "FINISHED").contains(query.timeField()))
                || (query.resultCode() != null && (query.resultCode().isEmpty() || query.resultCode().length() > 64))
                || (query.state() != null && !java.util.Set.of("READY", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "FAILED").contains(query.state()))
                || (query.triggerType() != null && !java.util.Set.of("MANUAL", "CRON").contains(query.triggerType())))
            throw new RunException(400, "INVALID_ARGUMENT", "运行筛选或分页参数不合法");
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("page", Integer.toString(query.page())); params.put("pageSize", Integer.toString(query.pageSize()));
        addQueryWindow(params, query.from(), query.to());
        if (query.timeField() != null) params.put("timeField", query.timeField());
        if (query.jobId() != null) params.put("jobId", query.jobId().toString());
        if (query.state() != null) params.put("state", query.state());
        if (query.triggerType() != null) params.put("triggerType", query.triggerType());
        if (query.runId() != null) params.put("runId", query.runId().toString());
        if (query.resultCode() != null) params.put("resultCode", query.resultCode());
        return scheduler.get("/internal/v1/runs/selectGlobal?" + encodeQuery(params));
    }
    @Override
    public JsonNode selectSummary(String from, String to) {
        String path = "/internal/v1/runs/selectSummary";
        var params = new java.util.LinkedHashMap<String, String>();
        addQueryWindow(params, from, to);
        if (!params.isEmpty()) path += "?" + encodeQuery(params);
        var result = scheduler.get(path);
        SchedulerClient.validateSummary(result);
        return result;
    }

    private static void addQueryWindow(Map<String, String> params, String from, String to) {
        if (from == null && to == null) return;
        try {
            if (from == null || to == null || !from.endsWith("Z") || !to.endsWith("Z"))
                throw new IllegalArgumentException();
            var start = Instant.parse(from);
            var end = Instant.parse(to);
            if (start.isBefore(EARLIEST_QUERY_TIME) || end.isAfter(LATEST_QUERY_TIME)
                    || !start.isBefore(end) || Duration.between(start, end).compareTo(MAX_QUERY_WINDOW) > 0)
                throw new IllegalArgumentException();
            params.put("from", start.toString());
            params.put("to", end.toString());
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new RunException(400, "INVALID_ARGUMENT", "时间窗须同时提供以 Z 结尾的 UTC 时间，位于公元 1000 至 9999 年，起点早于终点且不超过 7 天");
        }
    }

    private static String encodeQuery(Map<String, String> params) {
        return params.entrySet().stream().map(entry -> entry.getKey() + "="
                + java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    @Override public JsonNode selectByRequestKey(UUID key) {
        return publicRun(scheduler.get("/internal/v1/runs/selectByRequestKey?requestKey=" + key));
    }

    private JsonNode publicRun(JsonNode run) {
        if (!(run instanceof ObjectNode object) || !object.has("triggerActor")) return run;
        var copy = object.deepCopy();
        copy.remove("triggerActor");
        return copy;
    }
}
