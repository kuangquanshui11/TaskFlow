package com.taskflow.admin.run.service.impl;

import java.util.Map;
import java.util.UUID;
import com.taskflow.admin.job.service.IJobService;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.dto.RunRequests.Insert;
import com.taskflow.admin.run.exception.RunException;
import com.taskflow.admin.run.service.IRunService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RunServiceImpl implements IRunService {
    private final IJobService jobs;
    private final SchedulerClient scheduler;
    private final JsonMapper json;
    public RunServiceImpl(IJobService jobs, SchedulerClient scheduler, JsonMapper json) {
        this.jobs = jobs; this.scheduler = scheduler; this.json = json;
    }
    @Override
    public JsonNode insert(Insert request) {
        try {
            var previous = selectByRequestKey(request.requestKey());
            if (previous.path("jobId").longValue() != request.jobId()
                    || previous.path("configVersion").longValue() != request.configVersion())
                throw new RunException(409, "IDEMPOTENCY_CONFLICT", "该请求标识已用于其他任务或配置版本");
            return previous;
        } catch (RunException missing) {
            if (missing.status() != 404) throw missing;
        }
        // 权威配置由数据库读取。这个本地只读事务在调用 Scheduler 前已结束。
        var job = jobs.selectById(request.jobId());
        if (job.configVersion() != request.configVersion())
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
        return scheduler.post("/internal/v1/runs/insert", Map.of("requestKey", request.requestKey(), "snapshot", snapshot));
    }
    @Override
    public JsonNode select(long jobId, int page, int pageSize) {
        if (jobId < 1 || page < 1 || page > 1000000 || pageSize < 1 || pageSize > 100)
            throw new RunException(400, "INVALID_ARGUMENT", "运行分页参数不合法");
        return scheduler.get("/internal/v1/runs/select?jobId=" + jobId + "&page=" + page + "&pageSize=" + pageSize);
    }
    @Override public JsonNode selectById(UUID id) { return scheduler.get("/internal/v1/runs/selectById?id=" + id); }
    @Override
    public JsonNode selectSummary(String from, String to) {
        String path = "/internal/v1/runs/selectSummary";
        if (from != null || to != null) {
            try {
                if (from == null || to == null || !from.endsWith("Z") || !to.endsWith("Z")) throw new IllegalArgumentException();
                var start = java.time.Instant.parse(from); var end = java.time.Instant.parse(to);
                if (!start.isBefore(end) || java.time.Duration.between(start, end).compareTo(java.time.Duration.ofDays(7)) > 0)
                    throw new IllegalArgumentException();
                path += "?from=" + java.net.URLEncoder.encode(start.toString(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&to=" + java.net.URLEncoder.encode(end.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.time.format.DateTimeParseException | IllegalArgumentException invalid) {
                throw new RunException(400, "INVALID_ARGUMENT", "时间窗须同时提供以 Z 结尾的 UTC 时间、起点早于终点且不超过 7 天");
            }
        }
        var result = scheduler.get(path);
        SchedulerClient.validateSummary(result);
        return result;
    }
    @Override public JsonNode selectByRequestKey(UUID key) { return scheduler.get("/internal/v1/runs/selectByRequestKey?requestKey=" + key); }
}
