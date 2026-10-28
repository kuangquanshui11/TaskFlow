package com.taskflow.worker.execution.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionCompletion;
import com.taskflow.worker.execution.service.IDemoHandlerService;
import com.taskflow.worker.execution.service.IExecutionService;
import com.taskflow.worker.execution.service.IExecutionResultService;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExecutionServiceImpl implements IExecutionService {
    private final ExecutionMapper mapper;
    private final IDemoHandlerService handlers;
    private final SchedulerReportClient reports;
    private final IExecutionResultService results;
    private final ExecutionProperties properties;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final ExecutorService pool;
    private final ScheduledExecutorService timeouts;
    private final Semaphore slots;
    private final AtomicBoolean recovered = new AtomicBoolean();
    public ExecutionServiceImpl(ExecutionMapper mapper, IDemoHandlerService handlers, SchedulerReportClient reports,
                                ExecutionProperties properties, JsonMapper json, PlatformTransactionManager manager,
                                IExecutionResultService results) {
        this.mapper = mapper; this.handlers = handlers; this.reports = reports; this.properties = properties; this.json = json;
        this.results = results;
        transactions = new TransactionTemplate(manager);
        slots = new Semaphore(properties.parallelism());
        pool = new ThreadPoolExecutor(properties.parallelism(), properties.parallelism(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.parallelism()), Thread.ofPlatform().name("taskflow-handler-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        var timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("taskflow-timeout-", 0).factory());
        timer.setRemoveOnCancelPolicy(true); timeouts = timer;
    }

    @Override
    public synchronized Accepted insert(Dispatch request) {
        var encoded = json.writeValueAsString(request);
        var fingerprint = hash(encoded);
        var previous = mapper.find(request.attemptId());
        if (previous.isPresent()) return requireSame(previous.get(), fingerprint);
        if (!handlers.keys().contains(request.snapshot().handlerKey()))
            throw new ExecutionException(400, "HANDLER_NOT_REGISTERED", "处理器未注册");
        if (!request.expiresAt().isAfter(Instant.now()))
            throw new ExecutionException(409, "LEASE_EXPIRED", "派发租约已过期");
        var payload = request.snapshot().payload();
        if (payload != null && !payload.isNull() && (!payload.isObject()
                || payload.toString().getBytes(StandardCharsets.UTF_8).length > 65536))
            throw new ExecutionException(400, "INVALID_ARGUMENT", "执行参数必须为不超过 64 KiB 的 JSON 对象");
        try {
            transactions.executeWithoutResult(status -> {
                if (mapper.pendingCount() >= properties.maxPending())
                    throw new ExecutionException(429, "WORKER_BUSY", "Worker 待执行容量已满");
                mapper.insert(request.attemptId(), request.runId(), fingerprint, encoded, Instant.now());
            });
        } catch (DuplicateKeyException concurrentInsert) {
            return requireSame(mapper.find(request.attemptId()).orElseThrow(() -> concurrentInsert), fingerprint);
        }
        return new Accepted(request.attemptId(), "ACCEPTED");
    }
    @Override
    public Accepted selectById(UUID attemptId) {
        var row = mapper.find(attemptId).orElseThrow(() -> new ExecutionException(404, "EXECUTION_NOT_FOUND", "本地执行不存在"));
        return new Accepted(row.attemptId(), row.state());
    }
    @Override
    public void recover() {
        mapper.recoverRunning(Instant.now());
        recovered.set(true);
    }
    @Override
    public com.taskflow.worker.execution.model.ExecutionLoad selectLoad() { return mapper.selectLoad(); }
    @Override
    public void executePending() {
        if (!recovered.get()) return;
        for (var row : mapper.pending(properties.parallelism())) {
            if (!slots.tryAcquire()) break;
            boolean claimed = false;
            try {
                if (!mapper.claim(row.attemptId())) { slots.release(); continue; }
                claimed = true;
                var dispatch = json.readValue(row.dispatchJson(), Dispatch.class);
                pool.execute(() -> execute(dispatch));
            } catch (RuntimeException failure) {
                try {
                    if (!claimed) throw failure;
                    boolean rejected = failure instanceof RejectedExecutionException;
                    results.save(new ExecutionCompletion(row.attemptId(), row.runId(), "FAILED",
                            rejected ? "EXECUTOR_REJECTED" : "EXECUTION_PREPARATION_FAILED",
                            "Worker 未能启动 Handler，未执行业务", false, Instant.now()));
                } finally { slots.release(); }
            }
        }
    }
    private void execute(Dispatch dispatch) {
        String state = "SUCCEEDED", code = "SUCCEEDED", message = "";
        boolean retryable = false;
        var expired = new AtomicBoolean();
        var active = new AtomicBoolean(true);
        ScheduledFuture<?> timeout = null;
        try {
            long remaining = java.time.Duration.between(Instant.now(), dispatch.expiresAt()).toMillis()
                    - properties.requestTimeout().toMillis();
            long budget = Math.min(dispatch.snapshot().timeoutSeconds() * 1000L, remaining);
            if (budget <= 0) {
                state = "FAILED"; code = "LEASE_EXPIRED"; message = "排队期间租约已临近过期，未执行 Handler";
            } else {
                var runner = Thread.currentThread();
                var deadline = Instant.now().plusMillis(budget);
                timeout = timeouts.schedule(() -> {
                    synchronized (active) {
                        if (active.get()) { expired.set(true); runner.interrupt(); }
                    }
                }, budget, TimeUnit.MILLISECONDS);
                try {
                    message = handlers.execute(dispatch);
                } catch (InterruptedException interrupted) {
                    state = "FAILED"; code = "HANDLER_INTERRUPTED"; message = "执行线程被中断";
                } catch (IllegalArgumentException invalid) {
                    state = "FAILED"; code = "INVALID_HANDLER_ARGUMENT"; message = "处理器参数不合法";
                } catch (RuntimeException failed) {
                    state = "FAILED"; code = "HANDLER_FAILED"; message = "示例处理器执行失败"; retryable = true;
                } finally {
                    synchronized (active) { active.set(false); timeout.cancel(false); }
                    Thread.interrupted();
                }
                if (expired.get() || !Instant.now().isBefore(deadline)) {
                    state = "FAILED"; code = "EXECUTION_TIMEOUT"; message = "Handler 执行超时"; retryable = false;
                }
            }
            // 终态与待上报信息在同一条本地 SQL 中持久化；HTTP 上报由独立扫描完成。
            results.save(new ExecutionCompletion(dispatch.attemptId(), dispatch.runId(), state, code, message,
                    retryable, Instant.now()));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Execution persistence failed. attemptId={}, type={}",
                    dispatch.attemptId(), failure.getClass().getSimpleName());
        } finally {
            synchronized (active) { active.set(false); if (timeout != null) timeout.cancel(false); }
            Thread.interrupted();
            // Handler 真正退出且结果保存结束后才释放槽位，限制故障期间内存中的结果数量。
            slots.release();
        }
    }
    @Override
    public void reportPending() {
        var deadline = Instant.now().plus(properties.reportBudget());
        if (!recovered.get()) return;
        for (var row : mapper.reports(Instant.now(), properties.maxReportAttempts())) {
            if (!Instant.now().isBefore(deadline)) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Report budget exhausted; remaining rows deferred. budget={}", properties.reportBudget());
                return;
            }
            if (!mapper.reportClaim(row.attemptId(), row.reportCount(), Instant.now().plus(properties.reportRetryDelay()))) continue;
            var dispatch = json.readValue(row.dispatchJson(), Dispatch.class);
            var result = new Result(row.attemptId(), row.runId(), dispatch.leaseVersion(), row.state(),
                    row.resultCode(), row.message(), row.retryable());
            boolean confirmed = false;
            try { confirmed = reports.report(result); }
            catch (RuntimeException ignored) { }
            if (confirmed) mapper.reported(row.attemptId());
            else if (row.reportCount() + 1 >= properties.maxReportAttempts())
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Result reporting exhausted. attemptId={}", row.attemptId());
        }
    }
    private Accepted requireSame(com.taskflow.worker.execution.model.ExecutionRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint))
            throw new ExecutionException(409, "IDEMPOTENCY_CONFLICT", "同一 Attempt 对应不同派发内容");
        return new Accepted(row.attemptId(), row.state());
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @PreDestroy
    public void shutdown() { pool.shutdownNow(); timeouts.shutdownNow(); }
}
