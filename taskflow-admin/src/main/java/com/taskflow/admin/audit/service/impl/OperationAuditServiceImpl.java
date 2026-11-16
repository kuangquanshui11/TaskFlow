package com.taskflow.admin.audit.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.taskflow.admin.audit.mapper.OperationAuditMapper;
import com.taskflow.admin.audit.model.OperationAudit;
import com.taskflow.admin.audit.model.OperationAudit.*;
import com.taskflow.admin.audit.service.IOperationAuditService;
import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.run.client.SchedulerClient;
import com.taskflow.admin.run.exception.RunException;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class OperationAuditServiceImpl implements IOperationAuditService {
    private static final int MAX_CHECKS = 6;
    private static final Duration CHECK_DELAY = Duration.ofSeconds(30);
    private final OperationAuditMapper mapper;
    private final SchedulerClient scheduler;
    private final Tracer tracer;

    public OperationAuditServiceImpl(OperationAuditMapper mapper, SchedulerClient scheduler, Tracer tracer) {
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.tracer = tracer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void jobApplied(Action action, long jobId, long version) {
        recordJob(action, jobId, version, Status.APPLIED, "OK");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void jobRejected(Action action, Long jobId, Long version, String code) {
        recordJob(action, jobId, version, Status.REJECTED, code);
    }

    private void recordJob(Action action, Long jobId, Long version, Status status, String code) {
        var actor = actor();
        if (actor != null) mapper.insert(record(actor, action, jobId, version, null, status, code), null);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public OperationAudit beginManual(UUID key, long jobId, long version) {
        var actor = actor();
        if (actor == null) throw new RunException(403, "AUDIT_ACTOR_REQUIRED", "缺少认证操作者");
        var record = record(actor, Action.RUN_TRIGGER, jobId, version, key, Status.PENDING, "AWAITING_SCHEDULER");
        try {
            mapper.insert(record, record.occurredAt().plus(CHECK_DELAY));
            return record;
        } catch (DuplicateKeyException conflict) {
            var prior = mapper.byRequestKey(key).orElseThrow(() -> conflict);
            if (!prior.actor().equals(actor) || prior.jobId() != jobId || prior.configVersion() != version)
                throw new RunException(409, "IDEMPOTENCY_CONFLICT", "该请求标识已绑定其他运行、操作者或配置版本");
            return prior;
        }
    }

    @Override
    public void observeRun(OperationAudit audit, JsonNode run) {
        if (run == null || !run.isObject() || run.hasNonNull("sourceRunId")
                || run.path("jobId").asLong(-1) != audit.jobId() || run.path("configVersion").asLong(-1) != audit.configVersion()
                || !audit.requestKey().toString().equals(run.path("requestKey").asString(""))
                || !audit.actor().equals(run.path("triggerActor").asString(""))) return;
        try {
            var runId = UUID.fromString(run.path("id").asString(""));
            mapper.resolve(audit.id(), Status.APPLIED, "RUN_ACCEPTED", runId, Instant.now());
        } catch (IllegalArgumentException malformedIdentifier) {
            // 保留待确认，由后续只读查询重新核对。
        }
    }

    @Override
    public void rejectManual(OperationAudit audit, String code) {
        mapper.resolve(audit.id(), Status.REJECTED, code, null, Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void reconcile() {
        var deadline = Instant.now().plusSeconds(5);
        for (var audit : mapper.due(Instant.now(), 20)) {
            if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) return;
            if (audit.checkCount() >= MAX_CHECKS) {
                mapper.resolve(audit.id(), Status.UNCONFIRMED, "SCHEDULER_OUTCOME_UNKNOWN", null, Instant.now());
                continue;
            }
            var now = Instant.now();
            if (!mapper.claim(audit, now, now.plus(CHECK_DELAY))) continue;
            try {
                observeRun(audit, scheduler.get("/internal/v1/runs/selectByRequestKey?requestKey=" + audit.requestKey()));
            } catch (RunException unavailable) {
                // 404 或不可用只说明这次读取未确认，Scheduler 可能随后完成写入。
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page select(Query query) {
        var from = query.from();
        var to = query.to();
        if (from == null && to == null && query.requestKey() == null && query.runId() == null) {
            to = Instant.now();
            from = to.minus(Duration.ofHours(24));
        }
        if (query.page() < 1 || query.page() > 10000 || query.pageSize() < 1 || query.pageSize() > 100
                || query.jobId() != null && query.jobId() < 1
                || query.actor() != null && (query.actor().isBlank() || query.actor().length() > 255)
                || (from == null) != (to == null)
                || from != null && (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                    || from.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || to.isAfter(Instant.parse("9999-12-31T23:59:59Z"))))
            throw JobException.invalid("审计分页、过滤条件或时间范围不合法");
        var rows = mapper.select(new Query(from, to, query.actor(), query.action(), query.jobId(),
                query.runId(), query.requestKey(), query.page(), query.pageSize()));
        return new Page(rows.stream().limit(query.pageSize()).toList(), query.page(), query.pageSize(),
                rows.size() > query.pageSize());
    }

    private OperationAudit record(String actor, Action action, Long job, Long version, UUID key, Status status, String code) {
        var now = Instant.now();
        var span = tracer.currentSpan();
        return new OperationAudit(UUID.randomUUID(), actor, action, job, version, key, null, status, code,
                MDC.get("requestId"), span == null ? null : span.context().traceId(), now, now, 0);
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 真实 API 使用验签 JWT，测试与受信任的本地 Service 调用可使用已认证主体。
        if (authentication == null || !authentication.isAuthenticated()) return null;
        var subject = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : authentication.getName();
        return subject == null || subject.isBlank() ? null : subject;
    }
}
