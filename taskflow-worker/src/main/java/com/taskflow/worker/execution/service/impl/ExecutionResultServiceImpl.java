package com.taskflow.worker.execution.service.impl;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import com.taskflow.worker.execution.config.ResultPersistenceProperties;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionCompletion;
import com.taskflow.worker.execution.service.IExecutionResultService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExecutionResultServiceImpl implements IExecutionResultService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionResultServiceImpl.class);
    private final ExecutionMapper mapper;
    private final ResultPersistenceProperties properties;
    private final TransactionTemplate transactions;
    private final AtomicInteger saving = new AtomicInteger();
    private final Counter saved;
    private final Counter recovered;
    private final Counter exhausted;
    private final Counter conflicts;
    private final Counter interrupted;

    public ExecutionResultServiceImpl(ExecutionMapper mapper, ResultPersistenceProperties properties, MeterRegistry metrics,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        metrics.gauge("taskflow.worker.result.persistence.active", saving);
        saved = metrics.counter("taskflow.worker.result.persistence", "outcome", "saved");
        recovered = metrics.counter("taskflow.worker.result.persistence", "outcome", "recovered");
        exhausted = metrics.counter("taskflow.worker.result.persistence", "outcome", "exhausted");
        conflicts = metrics.counter("taskflow.worker.result.persistence", "outcome", "conflict");
        interrupted = metrics.counter("taskflow.worker.result.persistence", "outcome", "interrupted");
    }

    @Override
    public boolean save(ExecutionCompletion completion) {
        saving.incrementAndGet();
        try {
            for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
                try {
                    if (Boolean.TRUE.equals(transactions.execute(status -> mapper.finish(completion.attemptId(),
                            completion.state(), completion.resultCode(), completion.message(), completion.retryable(),
                            completion.handlerInvoked(), completion.finishedAt())))
                            || alreadySaved(completion)) {
                        saved.increment();
                        if (attempt > 1) {
                            recovered.increment();
                            log.info("code=RESULT_SAVE_RECOVERED attemptId={} runId={} attempts={}",
                                    completion.attemptId(), completion.runId(), attempt);
                        }
                        return true;
                    }
                    // 更新 0 行可能表示记录缺失或已有冲突终态，不能把它当作成功或覆盖终态。
                    conflicts.increment();
                    diagnostic("RESULT_SAVE_CONFLICT", completion, attempt, "STATE_CONFLICT");
                    return false;
                } catch (DataAccessException | TransactionException failure) {
                    if (attempt == properties.maxAttempts()) {
                        exhausted.increment();
                        diagnostic("RESULT_SAVE_EXHAUSTED", completion, attempt, failure.getClass().getSimpleName());
                        return false;
                    }
                    log.warn("code=RESULT_SAVE_RETRY attemptId={} runId={} attempts={} type={}",
                            completion.attemptId(), completion.runId(), attempt, failure.getClass().getSimpleName());
                    try {
                        // 无 SQL 事务包围等待；占用原执行槽位，使故障期间的结果保留有界。
                        Thread.sleep(properties.retryDelay());
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        interrupted.increment();
                        diagnostic("RESULT_SAVE_INTERRUPTED", completion, attempt, stopped.getClass().getSimpleName());
                        return false;
                    }
                }
            }
            throw new IllegalStateException("Unreachable result persistence state");
        } finally {
            saving.decrementAndGet();
        }
    }

    private boolean alreadySaved(ExecutionCompletion completion) {
        // 应对 SQL 已提交但连接确认丢失；重复保存相同终态不重置上报计数或时间。
        return mapper.find(completion.attemptId()).filter(row -> row.runId().equals(completion.runId())
                && row.state().equals(completion.state()) && Objects.equals(row.resultCode(), completion.resultCode())
                && Objects.equals(row.message(), completion.message()) && row.retryable() == completion.retryable()).isPresent();
    }

    private void diagnostic(String code, ExecutionCompletion completion, int attempts, String type) {
        // 不输出 Handler 参数、结果正文、SQL 异常正文或凭据。
        log.error("code={} attemptId={} runId={} intendedState={} resultCode={} retryable={} finishedAt={} attempts={} type={}",
                code, completion.attemptId(), completion.runId(), completion.state(), completion.resultCode(),
                completion.retryable(), completion.finishedAt(), attempts, type);
    }
}
