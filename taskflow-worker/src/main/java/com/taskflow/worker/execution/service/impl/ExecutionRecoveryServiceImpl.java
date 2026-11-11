package com.taskflow.worker.execution.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionCompletion;
import com.taskflow.worker.execution.service.IExecutionLeaseService;
import com.taskflow.worker.execution.service.IExecutionRecoveryService;
import com.taskflow.worker.execution.service.IExecutionResultService;
import com.taskflow.worker.execution.service.IExecutionResultService.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExecutionRecoveryServiceImpl implements IExecutionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryServiceImpl.class);
    private final ExecutionMapper mapper;
    private final IExecutionResultService results;
    private final IExecutionLeaseService leases;
    private final ExecutionProperties properties;
    private final TransactionTemplate transactions;
    // null 表示仍由领取或执行线程负责。同一条记录在移交恢复后继续占用原槽位。
    private final LinkedHashMap<UUID, ExecutionCompletion> tracked = new LinkedHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final Counter original;
    private final Counter unknown;

    public ExecutionRecoveryServiceImpl(ExecutionMapper mapper, IExecutionResultService results,
            IExecutionLeaseService leases, ExecutionProperties properties, PlatformTransactionManager manager,
            MeterRegistry metrics) {
        this.mapper = mapper;
        this.results = results;
        this.leases = leases;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        metrics.gauge("taskflow.worker.result.recovery.pending", pending);
        original = metrics.counter("taskflow.worker.result.recovery", "outcome", "original");
        unknown = metrics.counter("taskflow.worker.result.recovery", "outcome", "unknown");
    }

    @Override
    public boolean tryTrack(UUID attemptId) {
        synchronized (tracked) {
            if (tracked.size() >= properties.parallelism() || tracked.containsKey(attemptId)) return false;
            tracked.put(attemptId, null);
            return true;
        }
    }

    @Override
    public void release(UUID attemptId) {
        synchronized (tracked) {
            if (tracked.remove(attemptId) != null) pending.decrementAndGet();
        }
    }

    @Override
    public void defer(ExecutionCompletion completion) {
        synchronized (tracked) {
            if (!tracked.containsKey(completion.attemptId()) || tracked.get(completion.attemptId()) != null) {
                throw new IllegalStateException("Execution result requires an active slot");
            }
            tracked.put(completion.attemptId(), completion);
            pending.incrementAndGet();
        }
    }

    @Override
    public synchronized void scan() {
        var deferred = new ArrayList<ExecutionCompletion>();
        synchronized (tracked) {
            for (var completion : tracked.values()) if (completion != null) deferred.add(completion);
        }
        for (var completion : deferred) {
            try {
                recover(completion);
            } catch (RuntimeException unavailable) {
                synchronized (tracked) {
                    // 下轮先处理其他待恢复结果，避免单条记录持续阻塞队首。
                    tracked.remove(completion.attemptId());
                    tracked.put(completion.attemptId(), completion);
                }
                throw unavailable;
            }
        }

        var owner = leases.owner();
        // 在途执行至多 parallelism 条。再读取同等数量，为遗留记录留出扫描额度。
        for (var candidate : mapper.running(owner, properties.parallelism() * 2)) {
            synchronized (tracked) {
                if (tracked.containsKey(candidate.attemptId())) continue;
            }
            settleUnknown(candidate.attemptId(), candidate.runId());
        }
    }

    private void recover(ExecutionCompletion completion) {
        try {
            if (results.recover(completion) == Outcome.SAVED) {
                original.increment();
                log.info("code=RESULT_RECOVERED attemptId={} runId={}", completion.attemptId(), completion.runId());
            }
        } catch (DataIntegrityViolationException invalidResult) {
            // 例如结果正文超出列长度，使用受控短消息尝试收尾。写库仍失败时保留原槽位。
            settleUnknown(completion.attemptId(), completion.runId());
        }
        release(completion.attemptId());
    }

    private void settleUnknown(UUID attemptId, UUID runId) {
        if (Boolean.TRUE.equals(transactions.execute(status -> mapper.finishUnknown(attemptId, runId,
                leases.owner(), Instant.now())))) {
            unknown.increment();
            log.warn("code=RESULT_OUTCOME_UNKNOWN attemptId={} runId={}", attemptId, runId);
        }
    }
}
