package com.taskflow.scheduler.execution.service.impl;

import com.taskflow.scheduler.observability.config.AsyncTracing;
import com.taskflow.scheduler.observability.config.AsyncTracing.Context;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.execution.model.RunRecords.*;
import com.taskflow.scheduler.execution.model.RetryDelay;
import com.taskflow.scheduler.execution.model.OutcomeReview;
import com.taskflow.scheduler.execution.model.RunCancellation;
import com.taskflow.scheduler.execution.model.RunCancellation.Outcome;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunServiceImpl.class);
    private static final Instant EARLIEST_QUERY_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LATEST_QUERY_TIME = Instant.parse("9999-12-31T23:59:59.999Z");
    private static final Duration MAX_QUERY_WINDOW = Duration.ofDays(7);

    private final AsyncTracing tracing;
    private final RunDataMapper mapper;
    private final WorkerClient worker;
    private final RunProperties properties;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readCommittedTransactions;
    private final DispatchProperties dispatch;
    private final DispatchOutboxMapper outbox;
    private final com.taskflow.scheduler.node.service.IWorkerNodeService nodes;
    public RunServiceImpl(RunDataMapper mapper, WorkerClient worker, RunProperties properties,
                          JsonMapper json, PlatformTransactionManager transactionManager,
                          DispatchProperties dispatch, DispatchOutboxMapper outbox,com.taskflow.scheduler.node.service.IWorkerNodeService nodes, AsyncTracing tracing) {
        this.mapper = mapper; this.worker = worker; this.properties = properties; this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readCommittedTransactions = new TransactionTemplate(transactionManager);
        this.readCommittedTransactions.setIsolationLevel(Isolation.READ_COMMITTED.value());
        this.dispatch = dispatch; this.outbox = outbox;
        this.nodes=nodes;
        this.tracing = tracing;
    }

    @Override
    public RunView insert(Create request) {
        return insertManual(request.requestKey(), request.snapshot(), null, null, request.actor());
    }

    @Override
    public RunView insertRerun(Rerun request) {
        return insertManual(request.requestKey(), request.snapshot(), request.sourceRunId(), request.actor(), null);
    }

    private RunView insertManual(UUID requestKey, Snapshot snapshot, UUID sourceRunId, String actor, String triggerActor) {
        validate(snapshot);
        String encoded = json.writeValueAsString(snapshot);
        String fingerprint = fingerprint(encoded);
        var existing = mapper.byKey(requestKey);
        if (existing.isPresent()) return requireSame(existing.get(), fingerprint, sourceRunId, actor, triggerActor);
        try {
            UUID id = transactions.execute(status -> {
                if (sourceRunId != null) {
                    var source = mapper.lock(sourceRunId)
                            .orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "原运行记录不存在"));
                    if (source.jobId() != snapshot.jobId() || !canRerun(source, mapper.attempts(sourceRunId)))
                        throw new RunException(422, "RUN_RERUN_UNSAFE", "无法确认原运行的所有 Attempt 均未启动 Handler");
                }
                var now = Instant.now();
                var run = new RunRow(UUID.randomUUID(), snapshot.jobId(), snapshot.configVersion(),
                        requestKey, fingerprint, "READY", encoded, 1, null, null, now, now, "MANUAL", null, sourceRunId, actor, null, tracing.capture(), triggerActor);
                mapper.insert(run);
                insertAttempt(run, 1, now);
                return run.id();
            });
            tracing.tag("runId", id);
            tracing.tag("requestKey", requestKey);
            log.atInfo().addKeyValue("event", "RUN_CREATED").addKeyValue("runId", id)
                    .addKeyValue("requestKey", requestKey).log("Manual Run created");
            return selectById(id);
        } catch (DuplicateKeyException concurrentInsert) {
            var winner = mapper.byKey(requestKey).orElseThrow(() -> concurrentInsert);
            return requireSame(winner, fingerprint, sourceRunId, actor, triggerActor);
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
        var runId = UUID.randomUUID();
        try (var trace = tracing.start("run.schedule", Context.EMPTY, Map.of("runId", runId, "jobId", snapshot.jobId()))) {
            var run = new RunRow(runId, snapshot.jobId(), snapshot.configVersion(), UUID.randomUUID(),
                    fingerprint(encoded), "READY", encoded, 1, null, null, createdAt, createdAt, "CRON", scheduledAt,
                    null, null, null, tracing.capture(), null);
            mapper.insert(run);
            insertAttempt(run, 1, createdAt);
            return runId;
        }
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
                        row.resultCode(), row.message(), row.createdAt(), row.updatedAt(), row.triggerType(), row.scheduledAt(), row.finishedAt())).toList();
        return new Page(rows, mapper.count(jobId), page, pageSize);
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page selectGlobal(com.taskflow.scheduler.execution.dto.RunQuery query) {
        if (query.page() < 1 || query.page() > 10000 || query.pageSize() < 1 || query.pageSize() > 100
                || (query.jobId() != null && query.jobId() < 1)
                || (query.timeField() != null && !java.util.Set.of("CREATED", "FINISHED").contains(query.timeField()))
                || (query.resultCode() != null && (query.resultCode().isEmpty() || query.resultCode().length() > 64))
                || (query.state() != null && !java.util.Set.of("READY", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED", "SUCCEEDED", "FAILED", "CANCELLED").contains(query.state()))
                || (query.triggerType() != null && !java.util.Set.of("MANUAL", "CRON").contains(query.triggerType())))
            throw new RunException(400, "INVALID_ARGUMENT", "运行筛选或分页参数不合法");
        Instant from = null, to = null;
        if (query.from() != null || query.to() != null) {
            try {
                if (query.from() == null || query.to() == null || !query.from().endsWith("Z") || !query.to().endsWith("Z"))
                    throw new IllegalArgumentException();
                from = Instant.parse(query.from());
                to = Instant.parse(query.to());
            } catch (java.time.format.DateTimeParseException | IllegalArgumentException invalid) {
                throw new RunException(400, "INVALID_ARGUMENT", "时间窗须同时提供以 Z 结尾的 UTC 时间");
            }
            validateQueryWindow(from, to);
        } else if (query.runId() == null) {
            to = Instant.now(); from = to.minus(Duration.ofHours(24));
        }
        // 精确 ID 查询可跨历史；显式提供的时间、任务和状态仍取交集。记录与总数使用同一只读快照。
        var filter = new com.taskflow.scheduler.execution.model.RunFilter(from, to, query.jobId(), query.state(),
                query.triggerType(), query.runId(), query.resultCode(), "FINISHED".equals(query.timeField()));
        return new Page(mapper.selectGlobal(filter, query.page(), query.pageSize()), mapper.countGlobal(filter), query.page(), query.pageSize());
    }

    @Override
    public Overview selectSummary(Instant from, Instant to) {
        var observed = Instant.now();
        if (from == null && to == null) { to = observed; from = to.minus(Duration.ofHours(24)); }
        validateQueryWindow(from, to);
        // 多次读取不持有共同事务快照；观察时间不承诺各项统计完全同步。
        var states = mapper.summaryStates(from, to);
        var attempts = mapper.summaryAttempts(from, to);
        var samples = mapper.summaryLatencies(from, to);
        var failures = mapper.summaryFailures(from, to);
        var counts = new java.util.LinkedHashMap<String, Long>();
        for (var state : List.of("READY", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED", "SUCCEEDED", "FAILED", "CANCELLED")) counts.put(state, 0L);
        states.forEach(row -> counts.put(row.state(), row.count()));
        long succeeded = counts.get("SUCCEEDED"), failed = counts.get("FAILED");
        long judged = succeeded + failed;
        long finished = judged + counts.get("CANCELLED");
        long timed = states.stream().mapToLong(StateCount::timedCount).sum();
        var totalMillis = states.stream().map(StateCount::totalMillis).filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        long endToEndCount = states.stream().mapToLong(StateCount::endToEndCount).sum();
        var endToEndTotal = states.stream().map(StateCount::endToEndTotalMillis).filter(Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var endToEndSamples = mapper.summaryEndToEndLatencies(from, to);
        var rate = judged == 0 ? null : java.math.BigDecimal.valueOf(succeeded)
                .divide(java.math.BigDecimal.valueOf(judged), 4, java.math.RoundingMode.HALF_UP);
        return new Overview(from, to, observed, counts.values().stream().mapToLong(Long::longValue).sum(), counts,
                finished, succeeded, failed, rate, attempts.attemptTotal(), attempts.retriedRunCount(),
                latency(totalMillis, timed, samples), failures, latency(endToEndTotal, endToEndCount, endToEndSamples));
    }

    private static Latency latency(java.math.BigDecimal total, long count, List<Long> samples) {
        var sorted = samples.stream().sorted().toList();
        Long average = count == 0 ? null : total.divide(java.math.BigDecimal.valueOf(count), 0, java.math.RoundingMode.HALF_UP).longValue();
        Long p95 = sorted.isEmpty() ? null : sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        return new Latency(average, p95, sorted.size(), count > 10000);
    }

    private static void validateQueryWindow(Instant from, Instant to) {
        // Mapper 将半开窗口向上取整至毫秒，限制端点以免超出可绑定的日期范围。
        if (from == null || to == null || from.isBefore(EARLIEST_QUERY_TIME) || to.isAfter(LATEST_QUERY_TIME)
                || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_QUERY_WINDOW) > 0)
            throw new RunException(400, "INVALID_ARGUMENT", "时间窗必须同时提供，位于公元 1000 至 9999 年，起点早于终点且不超过 7 天");
    }

    @Override
    public void updateResult(Result result) {
        if (!java.util.Set.of("SUCCEEDED", "FAILED").contains(result.status()))
            throw new RunException(400, "INVALID_ARGUMENT", "不支持的执行终态");
        // 归档可能在定位 Attempt 与取得 Run 行锁之间提交，当前读需看到归档后的结果正文。
        readCommittedTransactions.executeWithoutResult(status -> {
            var pair = locked(result.attemptId());
            var run = pair.run(); var attempt = pair.attempt();
            if (!attempt.runId().equals(result.runId()) || attempt.leaseVersion() != result.leaseVersion()
                    || result.workerId() == null || result.sessionId() == null
                    || !result.workerId().equals(attempt.targetWorkerId())
                    || attempt.ownerWorkerId() != null && (!attempt.ownerWorkerId().equals(result.workerId())
                        || !attempt.ownerSessionId().equals(result.sessionId())))
                throw stale();
            if (attempt.ownerWorkerId() != null && run.currentAttempt() == attempt.attemptNumber()
                    && ("CANCEL_REQUESTED".equals(run.state()) || "CANCELLED".equals(run.state()))) {
                confirmCancellationResult(run, attempt, result);
                return;
            }
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
        for (var id : mapper.expiredCancellations(Instant.now())) {
            transactions.executeWithoutResult(status -> {
                var pair = locked(id);
                if (!"CANCEL_REQUESTED".equals(pair.run().state())) return;
                var cancellation = mapper.cancellation(pair.run().id(), true).orElseThrow(this::stale);
                var now = Instant.now();
                if (!cancellation.deadlineAt().isAfter(now))
                    finishCancellation(pair.run(), pair.attempt(), Outcome.STOP_UNCONFIRMED, now);
            });
        }
        for (var id : mapper.expired(Instant.now())) {
            transactions.executeWithoutResult(status -> {
                var pair = locked(id);
                if (!"CANCEL_REQUESTED".equals(pair.run().state())
                        && !pair.attempt().terminal() && pair.attempt().expiresAt() != null
                        && !pair.attempt().validAt(Instant.now()))
                    finish(pair.run(), pair.attempt(), "TIMED_OUT", "LEASE_EXPIRED", "执行租约已过期，迟到结果将被拒绝", properties.retryOnLeaseLoss());
            });
        }
        if (dispatch.rabbitmq()) { prepareMqDispatches(); return; }
        for (var id : mapper.due(Instant.now())) {
            var prepared = readCommittedTransactions.execute(status -> claim(id));
            if (prepared == null) continue;
            var command = prepared.command();
            try (var trace = tracing.start("dispatch.http", prepared.traceContext(), Map.of(
                    "runId", command.runId(), "attemptId", id, "workerId", prepared.target().workerId()))) {
                worker.dispatch(command);
                log.info("Worker accepted dispatch");
            } catch (RuntimeException failure) {
                boolean permanent = failure instanceof RunException remote && remote.status() >= 400
                        && remote.status() < 500 && remote.status() != 429;
                transactions.executeWithoutResult(status -> {
                    var pair = locked(id);
                    var now = Instant.now();
                    mapper.appendAttemptEvent(command.runId(), id, prepared.target().workerId(), command.leaseVersion(),
                            permanent ? "HTTP_DISPATCH_REJECTED" : "HTTP_DISPATCH_UNCONFIRMED", now,
                            prepared.dispatchNumber(), null, null, null);
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
            transactions.executeWithoutResult(status -> {
                confirmAccepted(locked(id), command.leaseVersion());
                mapper.appendAttemptEvent(command.runId(), id, prepared.target().workerId(), command.leaseVersion(),
                        "HTTP_DISPATCH_ACCEPTED", Instant.now(), prepared.dispatchNumber(), null, null, null);
            });
        }
    }

    private void prepareMqDispatches() {
        for (var id : mapper.mqDue(Instant.now())) {
            readCommittedTransactions.executeWithoutResult(status -> {
                var pair = locked(id);
                var attempt = pair.attempt();
                if (attempt.terminal() || !canDispatch(pair.run()) || pair.run().currentAttempt() != attempt.attemptNumber()) return;
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

    private record Prepared(Dispatch command, DispatchTarget target, int dispatchNumber, Context traceContext) { }
    private Prepared claim(UUID id) {
        var pair = locked(id); var run = pair.run(); var attempt = pair.attempt(); var now = Instant.now();
        if (attempt.terminal() || !canDispatch(run) || !java.util.Set.of("READY", "DISPATCHING").contains(attempt.state())
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
        if (!mapper.reserveExecutionSlot(run, "SERIAL".equals(snapshot.overlapPolicy()))) {
            if (!mapper.deferDispatch(attempt, now.plus(dispatch.nodeRecheckDelay()), now)) throw stale();
            return null;
        }
        if (attempt.targetWorkerId() == null) mapper.bindTarget(id, target.workerId());
        var expires = attempt.expiresAt() == null
                ? now.plusSeconds(snapshot.timeoutSeconds()).plus(properties.leaseGrace()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : attempt.expiresAt();
        mapper.dispatching(id, expires, now.plus(properties.requestTimeout()).plus(properties.dispatchRetryDelay()), now);
        int dispatchNumber = attempt.dispatchCount() + 1;
        mapper.appendAttemptEvent(run.id(), id, target.workerId(), attempt.leaseVersion(),
                dispatch.rabbitmq() ? "MQ_DISPATCH_PREPARED" : "HTTP_DISPATCH_CLAIMED", now,
                dispatchNumber, "DISPATCHING", null, expires);
        return new Prepared(new Dispatch(id, run.id(), attempt.attemptNumber(), attempt.leaseVersion(), expires, snapshot),
                target, dispatchNumber, run.traceContext());
    }

    private void confirmAccepted(Pair pair, long leaseVersion) {
        var run = pair.run(); var attempt = pair.attempt(); var now = Instant.now();
        if (!canDispatch(run) || run.currentAttempt() != attempt.attemptNumber() || attempt.leaseVersion() != leaseVersion
                || !java.util.Set.of("READY", "DISPATCHING").contains(attempt.state())
                || !attempt.validAt(now)) return;
        // SQL 没有完成 Attempt 转换时，不得推进 Run；两条转换仍处于同一本地事务。
        if (mapper.accepted(attempt, now)
                && !mapper.runState(run, "RUNNING", attempt.attemptNumber(), null, null, now)) throw stale();
    }

    private boolean canDispatch(RunRow run) { return java.util.Set.of("READY", "RUNNING", "RETRY_WAIT").contains(run.state()); }

    private void finish(RunRow run, Attempt attempt, String state, String code, String message, boolean retryable) {
        if (run.currentAttempt() != attempt.attemptNumber() || !canDispatch(run)) throw stale();
        var now = Instant.now();
        if (!mapper.finish(attempt, state, code, message, now)) throw stale();
        outbox.cancel(attempt.id());
        mapper.appendAttemptEvent(run.id(), attempt.id(), attempt.targetWorkerId(), attempt.leaseVersion(),
                "ATTEMPT_FINISHED", now, null, state, code, null);
        var snapshot = json.readValue(run.snapshotJson(), Snapshot.class);
        boolean unknown = !state.equals("SUCCEEDED") && OutcomeReview.unknown(code, attempt.ownerWorkerId() != null);
        if (unknown) mapper.recordUnknownOutcome(attempt, code, now);
        if (!state.equals("SUCCEEDED") && retryable && !(unknown && Boolean.TRUE.equals(snapshot.pauseOnUnknownOutcome()))
                && attempt.attemptNumber() < snapshot.maxAttempts()) {
            int next = attempt.attemptNumber() + 1;
            var delay = RetryDelay.calculate(snapshot.retryPolicy(),
                    snapshot.retryIntervalSeconds(), snapshot.retryMaxIntervalSeconds(), attempt.attemptNumber(),
                    ThreadLocalRandom.current());
            insertAttempt(run, next, now.plus(delay));
            if (!mapper.runState(run, "RETRY_WAIT", next, code, message, now)) throw stale();
        } else {
            var finalState = state.equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED";
            if (!mapper.runState(run, finalState, attempt.attemptNumber(), code, message, now)) throw stale();
            mapper.releaseExecutionSlot(run.id());
            mapper.appendAttemptEvent(run.id(), attempt.id(), attempt.targetWorkerId(), attempt.leaseVersion(),
                    "RUN_FINISHED", now, null, finalState, code, null);
        }
    }
    private Attempt freshAttempt(UUID runId, int number, Instant when) {
        return new Attempt(UUID.randomUUID(), runId, number, number, "READY", 0, when, null, null, null, null, null);
    }
    private void insertAttempt(RunRow run, int number, Instant when) {
        var attempt = freshAttempt(run.id(), number, when);
        mapper.insertAttempt(attempt);
        // 与 Run/Attempt 同一事务记录发送意图，租约及不可变消息在实际派发时一起准备。
        if (dispatch.rabbitmq()) outbox.insert(attempt.id(), when, run.traceContext());
    }
    private record Pair(RunRow run, Attempt attempt) { }

    @Override
    public RunView updateCancellation(Cancel request) {
        try {
            return readCommittedTransactions.execute(status -> cancelRun(request));
        } catch (DuplicateKeyException conflict) {
            throw cancellationConflict();
        }
    }

    private RunView cancelRun(Cancel request) {
        var run = mapper.lock(request.runId()).orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "运行不存在"));
        var existing = mapper.cancellation(run.id(), false).orElse(null);
        var reason = request.reason().strip();
        if (existing != null) {
            if (!existing.requestKey().equals(request.requestKey()) || !existing.actor().equals(request.actor())
                    || !existing.reason().equals(reason)) throw cancellationConflict();
            return view(run);
        }
        if (!canDispatch(run)) throw new RunException(409, "RUN_NOT_CANCELLABLE", "运行已进入终态");
        var attempt = mapper.lockCurrentAttempt(run).orElseThrow(this::stale);
        if (attempt.terminal()) throw stale();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var deadline = now.plus(properties.leaseGrace());
        if (attempt.expiresAt() != null && deadline.isAfter(attempt.expiresAt())) deadline = attempt.expiresAt();
        mapper.insertCancellation(new RunCancellation(run.id(), attempt.id(), request.requestKey(), reason, request.actor(),
                now, deadline, Outcome.REQUESTED, null, null, null, null));
        outbox.cancel(attempt.id());
        mapper.appendAttemptEvent(run.id(), attempt.id(), attempt.targetWorkerId(), attempt.leaseVersion(),
                "RUN_CANCEL_REQUESTED", now, null, "CANCEL_REQUESTED", null, deadline);
        if (attempt.ownerWorkerId() == null) {
            finishCancellation(run, attempt, Outcome.BEFORE_START, now);
        } else if (!mapper.runState(run, "CANCEL_REQUESTED", attempt.attemptNumber(), "RUN_CANCEL_REQUESTED",
                "等待执行者报告停止", now)) {
            throw stale();
        }
        return view(mapper.find(run.id()).orElseThrow(this::stale));
    }

    private RunException cancellationConflict() {
        return new RunException(409, "CANCELLATION_CONFLICT", "取消请求标识或 Run 已绑定其他取消操作");
    }

    private void confirmCancellationResult(RunRow run, Attempt attempt, Result result) {
        // 上报身份已按原 Worker、会话与租约版本校验。取消后，报告只证明执行退出或提供迟到证据。
        var cancellation = mapper.cancellation(run.id(), true).orElseThrow(this::stale);
        if (!cancellation.attemptId().equals(attempt.id())) throw stale();
        if (cancellation.workerResultAt() != null) {
            if (cancellation.workerResultStatus().equals(result.status())
                    && cancellation.workerResultCode().equals(result.resultCode())) return;
            throw stale();
        }
        var now = Instant.now();
        if ("CANCEL_REQUESTED".equals(run.state())) {
            var outcome = cancellation.deadlineAt().isAfter(now) ? Outcome.STOP_CONFIRMED : Outcome.STOP_UNCONFIRMED;
            finishCancellation(run, attempt, outcome, now);
        }
        if (!mapper.recordCancellationResult(run.id(), result.status(), result.resultCode(), now)) throw stale();
        mapper.appendAttemptEvent(run.id(), attempt.id(), result.workerId(), attempt.leaseVersion(),
                "CANCEL_WORKER_RESULT", now, null, result.status(), result.resultCode(), null);
    }

    private void finishCancellation(RunRow run, Attempt attempt, Outcome outcome, Instant now) {
        String code = switch (outcome) {
            case BEFORE_START -> "CANCELLED_BEFORE_START";
            case STOP_CONFIRMED -> "CANCEL_STOP_CONFIRMED";
            case STOP_UNCONFIRMED -> "CANCEL_STOP_UNCONFIRMED";
            case REQUESTED -> throw new IllegalArgumentException("取消尚未结束");
        };
        String message = switch (outcome) {
            case BEFORE_START -> "取消时尚未授予此 Attempt 执行租约";
            case STOP_CONFIRMED -> "已收到执行者结束报告，外部副作用待核对";
            case STOP_UNCONFIRMED -> "停止确认超时，外部副作用待核对";
            case REQUESTED -> throw new IllegalArgumentException("取消尚未结束");
        };
        if (!mapper.finish(attempt, "CANCELLED", code, message, now)
                || !mapper.runState(run, "CANCELLED", attempt.attemptNumber(), code, message, now)
                || !mapper.finishCancellation(run.id(), outcome, now)) throw stale();
        mapper.releaseExecutionSlot(run.id());
        if (outcome != Outcome.BEFORE_START) mapper.recordUnknownOutcome(attempt, code, now);
        mapper.appendAttemptEvent(run.id(), attempt.id(), attempt.targetWorkerId(), attempt.leaseVersion(),
                "ATTEMPT_FINISHED", now, null, "CANCELLED", code, null);
        mapper.appendAttemptEvent(run.id(), attempt.id(), attempt.targetWorkerId(), attempt.leaseVersion(),
                "RUN_FINISHED", now, null, "CANCELLED", code, null);
    }

    @Override
    public OutcomeReview.Review updateOutcomeReview(ReviewOutcome request) {
        var proposed = new OutcomeReview.Review(request.requestKey(), request.runId(), request.attemptId(),
                request.decision(), request.evidence().strip(), request.actor(), Instant.now());
        try {
            return readCommittedTransactions.execute(status -> {
                var pair = locked(request.attemptId());
                if (!pair.run().id().equals(request.runId())) throw outcomeCaseNotFound();
                var prior = mapper.outcomeReview(request.requestKey());
                if (prior.isPresent()) return requireSameReview(prior.get(), proposed);
                var reviewCase = mapper.outcomeCases(request.runId()).stream()
                        .filter(candidate -> candidate.attemptId().equals(request.attemptId())).findFirst()
                        .orElseThrow(this::outcomeCaseNotFound);
                if (reviewCase.confirmed())
                    throw new RunException(409, "OUTCOME_ALREADY_CONFIRMED", "该 Attempt 的副作用已核对");
                mapper.insertOutcomeReview(proposed);
                return mapper.outcomeReview(request.requestKey()).orElseThrow();
            });
        } catch (DuplicateKeyException duplicate) {
            var prior = mapper.outcomeReview(request.requestKey()).orElseThrow(() -> duplicate);
            return requireSameReview(prior, proposed);
        }
    }

    private OutcomeReview.Review requireSameReview(OutcomeReview.Review stored, OutcomeReview.Review proposed) {
        if (!stored.runId().equals(proposed.runId()) || !stored.attemptId().equals(proposed.attemptId())
                || stored.decision() != proposed.decision() || !stored.actor().equals(proposed.actor())
                || !stored.evidence().equals(proposed.evidence()))
            throw new RunException(409, "IDEMPOTENCY_CONFLICT", "请求标识已绑定其他核对内容");
        return stored;
    }

    private RunException outcomeCaseNotFound() {
        return new RunException(404, "OUTCOME_CASE_NOT_FOUND", "该运行未找到对应的副作用核对记录");
    }
    private Pair locked(UUID id) {
        var initial = mapper.attempt(id).orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "执行尝试不存在"));
        var run = mapper.lock(initial.runId()).orElseThrow(() -> new RunException(404, "RUN_NOT_FOUND", "运行不存在"));
        // 首次普通查询只定位不可变 runId；决策必须使用锁 Run 后的当前读，避免 RR 快照保留旧状态。
        return new Pair(run, mapper.lockAttempt(id).orElseThrow(() -> new RunException(404, "ATTEMPT_NOT_FOUND", "执行尝试不存在")));
    }
    private RunView view(RunRow row) {
        var attempts = mapper.attempts(row.id());
        return new RunView(row.id(), row.jobId(), row.configVersion(), row.requestKey(), row.state(),
                json.readValue(row.snapshotJson(), Snapshot.class), attempts, row.resultCode(),
                row.message(), row.createdAt(), row.updatedAt(), row.triggerType(), row.scheduledAt(),
                row.sourceRunId(), row.rerunActor(), canRerun(row, attempts), row.traceContext().traceId(),
                new RunTiming(elapsedMillis(row.createdAt(), row.finishedAt()), elapsedMillis(row.scheduledAt(), row.createdAt()),
                        elapsedMillis(row.createdAt(), attempts.isEmpty() ? null : attempts.getFirst().startedAt())), row.triggerActor(),
                mapper.outcomeCases(row.id()), mapper.cancellation(row.id(), false).orElse(null));
    }

    static Long elapsedMillis(Instant from, Instant to) {
        return from == null || to == null || to.isBefore(from) ? null : Duration.between(from, to).toMillis();
    }
    private static boolean canRerun(RunRow row, List<Attempt> attempts) {
        // Worker 只在领取执行租约之前使用这两个结果码。旧记录缺少确定证据时按不可重跑处理。
        return "FAILED".equals(row.state()) && row.currentAttempt() > 0 && attempts.size() == row.currentAttempt()
                && attempts.stream().allMatch(attempt -> "FAILED".equals(attempt.state()) && attempt.ownerWorkerId() == null
                    && ("EXECUTOR_REJECTED".equals(attempt.resultCode()) || "EXECUTION_PREPARATION_FAILED".equals(attempt.resultCode())));
    }
    private RunView requireSame(RunRow row, String fingerprint, UUID sourceRunId, String actor, String triggerActor) {
        if (!row.fingerprint().equals(fingerprint) || !Objects.equals(row.sourceRunId(), sourceRunId)
                || !Objects.equals(row.rerunActor(), actor)
                || row.triggerActor() != null && !Objects.equals(row.triggerActor(), triggerActor))
            throw new RunException(409, "IDEMPOTENCY_CONFLICT", "相同请求标识对应不同配置或运行来源");
        return view(row);
    }
    private void validate(Snapshot snapshot) {
        if (!snapshot.isRetryPolicyValid())
            throw new RunException(400, "INVALID_ARGUMENT", "重试间隔上限与策略不匹配");
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
