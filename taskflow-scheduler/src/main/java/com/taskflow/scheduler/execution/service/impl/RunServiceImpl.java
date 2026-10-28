package com.taskflow.scheduler.execution.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.*;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.dto.DispatchEvent;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.node.model.DispatchTarget;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RunServiceImpl implements IRunService {
    private final RunDataMapper mapper;
    private final WorkerClient worker;
    private final RunProperties properties;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final DispatchProperties dispatch;
    private final DispatchOutboxMapper outbox;
    private final com.taskflow.scheduler.node.service.IWorkerNodeService nodes;
    public RunServiceImpl(RunDataMapper mapper, WorkerClient worker, RunProperties properties,
                          JsonMapper json, PlatformTransactionManager transactionManager,
                          DispatchProperties dispatch, DispatchOutboxMapper outbox,com.taskflow.scheduler.node.service.IWorkerNodeService nodes) {
        this.mapper = mapper; this.worker = worker; this.properties = properties; this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dispatch = dispatch; this.outbox = outbox;
        this.nodes=nodes;
    }

    @Override
    public RunView insert(Create request) {
        validate(request.snapshot());
        String encoded = json.writeValueAsString(request.snapshot());
        String fingerprint = fingerprint(encoded);
        var existing = mapper.byKey(request.requestKey());
        if (existing.isPresent()) return requireSame(existing.get(), fingerprint);
        try {
            UUID id = transactions.execute(status -> {
                var now = Instant.now();
                var run = new RunRow(UUID.randomUUID(), request.snapshot().jobId(), request.snapshot().configVersion(),
                        request.requestKey(), fingerprint, "READY", encoded, 1, null, null, now, now, "MANUAL", null);
                mapper.insert(run);
                insertAttempt(run.id(), 1, now);
                return run.id();
            });
            return selectById(id);
        } catch (DuplicateKeyException concurrentInsert) {
            var winner = mapper.byKey(request.requestKey()).orElseThrow(() -> concurrentInsert);
            return requireSame(winner, fingerprint);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID insertScheduled(Snapshot snapshot, Instant scheduledAt, Instant createdAt) {
        if (scheduledAt == null || createdAt == null || !"CRON".equals(snapshot.scheduleType()) || !snapshot.enabled())
            throw new IllegalArgumentException("定时执行必须携带有效计划时间和启用的 Cron 快照");
        // 同一 Job 的同一计划时间沿用已有 Run，不因重扫或配置版本变化生成第二轮执行。
        var existing = mapper.bySchedule(snapshot.jobId(), scheduledAt);
        if (existing.isPresent()) return existing.get().id();
        validate(snapshot);
        var encoded = json.writeValueAsString(snapshot);
        var run = new RunRow(UUID.randomUUID(), snapshot.jobId(), snapshot.configVersion(), UUID.randomUUID(),
                fingerprint(encoded), "READY", encoded, 1, null, null, createdAt, createdAt, "CRON", scheduledAt);
        mapper.insert(run);
        insertAttempt(run.id(), 1, createdAt);
        return run.id();
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunView selectById(UUID id) {
        return view(mapper.find(id).orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "运行记录不存在")));
    }
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunView selectByRequestKey(UUID key) {
        return view(mapper.byKey(key).orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "原提交请求尚未找到")));
    }
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page select(long jobId, int page, int pageSize) {
        if (jobId < 1 || page < 1 || page > 1000000 || pageSize < 1 || pageSize > 100)
            throw new RunException(400, "INVALID_ARGUMENT", "运行分页参数不合法");
        var rows = mapper.select(jobId, page, pageSize).stream()
                .map(row -> new Summary(row.id(), row.jobId(), row.configVersion(), row.state(), row.currentAttempt(),
                        row.resultCode(), row.message(), row.createdAt(), row.updatedAt(), row.triggerType(), row.scheduledAt())).toList();
        return new Page(rows, mapper.count(jobId), page, pageSize);
    }

    @Override
    public void updateResult(Result result) {
        if (!java.util.Set.of("SUCCEEDED", "FAILED").contains(result.status()))
            throw new RunException(400, "INVALID_ARGUMENT", "不支持的执行终态");
        transactions.executeWithoutResult(status -> {
            var pair = locked(result.attemptId());
            var run = pair.run(); var attempt = pair.attempt();
            if (!attempt.runId().equals(result.runId()) || attempt.leaseVersion() != result.leaseVersion()
                    || result.workerId() == null || result.sessionId() == null
                    || !result.workerId().equals(attempt.targetWorkerId())
                    || attempt.ownerWorkerId() != null && (!attempt.ownerWorkerId().equals(result.workerId())
                        || !attempt.ownerSessionId().equals(result.sessionId())))
                throw stale();
            // 已确认的相同终态可以在租约到期或后续重试启动后再次确认，不能再次改变状态。
            if (attempt.terminal()) {
                if (attempt.state().equals(result.status()) && java.util.Objects.equals(attempt.resultCode(), result.resultCode())
                        && java.util.Objects.equals(attempt.message(), result.message())) return;
                throw stale();
            }
            if (run.currentAttempt() != attempt.attemptNumber() || attempt.expiresAt() == null
                    || !attempt.validAt(Instant.now())
                    || !java.util.Set.of("DISPATCHING", "RUNNING").contains(attempt.state())) throw stale();
            finish(run, attempt, result.status(), result.resultCode(), result.message(), result.retryable());
        });
    }

    @Override
    public void dispatchPending() {
        for (var id : mapper.expired(Instant.now())) {
            transactions.executeWithoutResult(status -> {
                var pair = locked(id);
                if (!pair.attempt().terminal() && pair.attempt().expiresAt() != null
                        && !pair.attempt().validAt(Instant.now()))
                    finish(pair.run(), pair.attempt(), "TIMED_OUT", "LEASE_EXPIRED", "执行租约已过期，迟到结果将被拒绝", properties.retryOnLeaseLoss());
            });
        }
        if (dispatch.rabbitmq()) { prepareMqDispatches(); return; }
        for (var id : mapper.due(Instant.now())) {
            var prepared = transactions.execute(status -> claim(id));
            if (prepared == null) continue;
            var command = prepared.command();
            try {
                worker.dispatch(command);
            } catch (RuntimeException failure) {
                boolean permanent = failure instanceof RunException remote && remote.status() >= 400
                        && remote.status() < 500 && remote.status() != 429;
                transactions.executeWithoutResult(status -> {
                    var pair = locked(id);
                    // Worker 已领取执行资格比派发响应更强；响应丢失不能终结正在执行的工作。
                    if (pair.attempt().terminal() || pair.attempt().ownerWorkerId() != null) return;
                    if (permanent || pair.attempt().dispatchCount() >= properties.maxDispatchAttempts()) {
                        finish(pair.run(), pair.attempt(), "FAILED", permanent ? "DISPATCH_REJECTED" : "DISPATCH_UNCONFIRMED",
                                permanent ? "Worker 拒绝执行请求" : "有限派发次数已耗尽，未确认业务执行结果", false);
                    } else {
                        mapper.ready(id, Instant.now().plus(properties.dispatchRetryDelay()));
                    }
                });
                continue;
            }
            // 本地确认事务失败保留原 Attempt 等待恢复，不能归类为 Worker 拒绝。
            transactions.executeWithoutResult(status -> confirmAccepted(locked(id), command.leaseVersion()));
        }
    }

    private void prepareMqDispatches() {
        for (var id : mapper.mqDue(Instant.now())) {
            transactions.executeWithoutResult(status -> {
                var pair = locked(id);
                var attempt = pair.attempt();
                if (attempt.terminal() || terminal(pair.run()) || pair.run().currentAttempt() != attempt.attemptNumber()) return;
                // 先锁 Run、Attempt，再访问 Outbox；Relay 只锁 Outbox，不反向锁业务记录。
                var event = outbox.find(id, true).orElseThrow();
                switch (event.state()) {
                    case "WAITING" -> {
                        var prepared = claim(id);
                        if (prepared != null) outbox.prepare(id, json.writeValueAsString(new DispatchEvent(1, id, prepared.command())),
                                prepared.command().expiresAt(), Instant.now(), prepared.target());
                    }
                    case "PUBLISHED" -> {
                        // 表示代理已接管消息，不代表 Handler 已开始或已成功。
                        var published = json.readValue(event.messageJson(), DispatchEvent.class).command();
                        confirmAccepted(pair, published.leaseVersion());
                    }
                    case "FAILED" -> finish(pair.run(), attempt, "FAILED", "MQ_PUBLISH_UNCONFIRMED",
                            "有限发布次数已耗尽，业务执行结果未确认", false);
                    default -> { }
                }
            });
        }
    }

    private record Prepared(Dispatch command, DispatchTarget target) { }
    private Prepared claim(UUID id) {
        var pair = locked(id); var run = pair.run(); var attempt = pair.attempt(); var now = Instant.now();
        if (attempt.terminal() || terminal(run) || !java.util.Set.of("READY", "DISPATCHING").contains(attempt.state())
                || attempt.nextDispatchAt().isAfter(now) || run.currentAttempt() != attempt.attemptNumber()) return null;
        if (attempt.expiresAt() != null && !attempt.validAt(now)) {
            finish(run, attempt, "TIMED_OUT", "LEASE_EXPIRED", "执行租约已过期", properties.retryOnLeaseLoss()); return null;
        }
        if (attempt.dispatchCount() >= properties.maxDispatchAttempts()) {
            finish(run, attempt, "FAILED", "DISPATCH_UNCONFIRMED", "有限派发次数已耗尽，未确认业务执行结果", false); return null;
        }
        var snapshot = json.readValue(run.snapshotJson(), Snapshot.class);
        DispatchTarget target = null;
        if (attempt.targetWorkerId() == null && attempt.expiresAt() == null) {
            target = nodes.selectTarget(snapshot.handlerKey()).orElse(null);
        } else if (attempt.targetWorkerId() != null && !dispatch.rabbitmq() && nodes.canDispatch(snapshot.handlerKey())) {
            // HTTP 保留固定地址直连；重试沿用第一次绑定的身份。
            target = new DispatchTarget(attempt.targetWorkerId(), null, null, null, null);
        }
        if (target == null) {
            // 让本批不可派发记录退出当前候选，后续正常任务才有机会；不领取租约或消耗次数。
            if (!mapper.deferDispatch(attempt, now.plus(dispatch.nodeRecheckDelay()), now)) throw stale();
            return null;
        }
        if (attempt.targetWorkerId() == null) mapper.bindTarget(id, target.workerId());
        var expires = attempt.expiresAt() == null
                ? now.plusSeconds(snapshot.timeoutSeconds()).plus(properties.leaseGrace()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : attempt.expiresAt();
        mapper.dispatching(id, expires, now.plus(properties.requestTimeout()).plus(properties.dispatchRetryDelay()), now);
        return new Prepared(new Dispatch(id, run.id(), attempt.attemptNumber(), attempt.leaseVersion(), expires, snapshot), target);
    }

    private void confirmAccepted(Pair pair, long leaseVersion) {
        var run = pair.run(); var attempt = pair.attempt(); var now = Instant.now();
        if (terminal(run) || run.currentAttempt() != attempt.attemptNumber() || attempt.leaseVersion() != leaseVersion
                || !java.util.Set.of("READY", "DISPATCHING").contains(attempt.state())
                || !attempt.validAt(now)) return;
        // SQL 没有完成 Attempt 转换时，不得推进 Run；两条转换仍处于同一本地事务。
        if (mapper.accepted(attempt, now)
                && !mapper.runState(run, "RUNNING", attempt.attemptNumber(), null, null, now)) throw stale();
    }

    private boolean terminal(RunRow run) { return java.util.Set.of("SUCCEEDED", "FAILED").contains(run.state()); }

    private void finish(RunRow run, Attempt attempt, String state, String code, String message, boolean retryable) {
        if (run.currentAttempt() != attempt.attemptNumber() || terminal(run)) throw stale();
        var now = Instant.now();
        if (!mapper.finish(attempt, state, code, message, now)) throw stale();
        outbox.cancel(attempt.id());
        var snapshot = json.readValue(run.snapshotJson(), Snapshot.class);
        if (!state.equals("SUCCEEDED") && retryable && attempt.attemptNumber() < snapshot.maxAttempts()) {
            int next = attempt.attemptNumber() + 1;
            insertAttempt(run.id(), next, now.plusSeconds(snapshot.retryIntervalSeconds()));
            if (!mapper.runState(run, "RETRY_WAIT", next, code, message, now)) throw stale();
        } else {
            if (!mapper.runState(run, state.equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED", attempt.attemptNumber(), code, message, now)) throw stale();
        }
    }
    private Attempt freshAttempt(UUID runId, int number, Instant when) {
        return new Attempt(UUID.randomUUID(), runId, number, number, "READY", 0, when, null, null, null, null, null);
    }
    private void insertAttempt(UUID runId, int number, Instant when) {
        var attempt = freshAttempt(runId, number, when);
        mapper.insertAttempt(attempt);
        // 与 Run/Attempt 同一事务记录发送意图，租约及不可变消息在实际派发时一起准备。
        if (dispatch.rabbitmq()) outbox.insert(attempt.id(), when);
    }
    private record Pair(RunRow run, Attempt attempt) { }
    private Pair locked(UUID id) {
        var initial = mapper.attempt(id).orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "执行尝试不存在"));
        var run = mapper.lock(initial.runId()).orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "运行不存在"));
        // 首次普通查询只定位不可变 runId；决策必须使用锁 Run 后的当前读，避免 RR 快照保留旧状态。
        return new Pair(run, mapper.lockAttempt(id).orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "执行尝试不存在")));
    }
    private RunView view(RunRow row) {
        return new RunView(row.id(), row.jobId(), row.configVersion(), row.requestKey(), row.state(),
                json.readValue(row.snapshotJson(), Snapshot.class), mapper.attempts(row.id()), row.resultCode(),
                row.message(), row.createdAt(), row.updatedAt(), row.triggerType(), row.scheduledAt());
    }
    private RunView requireSame(RunRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint))
            throw new RunException(409, "IDEMPOTENCY_CONFLICT", "相同请求标识对应不同配置");
        return view(row);
    }
    private void validate(Snapshot snapshot) {
        if (snapshot.payload() != null && !snapshot.payload().isNull() && !snapshot.payload().isObject())
            throw new RunException(400, "INVALID_ARGUMENT", "执行参数必须为 JSON 对象");
        if (snapshot.payload() != null && snapshot.payload().toString().getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new RunException(400, "INVALID_ARGUMENT", "执行参数超过 64 KiB");
    }
    private String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private RunException stale() { return new RunException(409, "STALE_ATTEMPT", "执行状态、当前尝试或有效租约不匹配"); }
}
