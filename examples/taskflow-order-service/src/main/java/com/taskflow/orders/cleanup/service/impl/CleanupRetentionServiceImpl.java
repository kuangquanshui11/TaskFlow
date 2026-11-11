package com.taskflow.orders.cleanup.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.taskflow.orders.cleanup.config.CleanupProperties;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.RetentionCandidates;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.RetentionPreview;
import com.taskflow.orders.cleanup.mapper.CleanupMapper;
import com.taskflow.orders.cleanup.service.ICleanupRetentionService;
import com.taskflow.orders.inventory.mapper.StockOutboxFailureMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.taskflow.orders.cleanup.dto.CleanupProtocol.AUDIT_RETENTION_DAYS;

@Service
public class CleanupRetentionServiceImpl implements ICleanupRetentionService {
    private final CleanupMapper cleanup;
    private final StockOutboxFailureMapper failures;
    private final CleanupProperties properties;
    private final TransactionTemplate transactions;

    public CleanupRetentionServiceImpl(CleanupMapper cleanup, StockOutboxFailureMapper failures,
                                       CleanupProperties properties, PlatformTransactionManager manager) {
        this.cleanup = cleanup;
        this.failures = failures;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public RetentionPreview preview() {
        var cutoff = cutoff();
        int batchSize = properties.retentionBatchSize();
        return new RetentionPreview(cutoff,
                candidates(cleanup.previewExpiredRuns(cutoff, batchSize + 1).size(), batchSize),
                candidates(failures.previewExpired(cutoff, batchSize + 1).size(), batchSize));
    }

    @Override
    @Scheduled(fixedDelayString = "${order.cleanup.retention-scan-delay}",
            initialDelayString = "${order.cleanup.retention-scan-delay}")
    public void prune() {
        if (!properties.retentionEnabled()) return;

        var cutoff = cutoff();
        int batchSize = properties.retentionBatchSize();
        for (var key : cleanup.previewExpiredRuns(cutoff, batchSize)) {
            transactions.executeWithoutResult(status -> {
                // 按主键锁定并复核候选，避免将并发提交的活动批次清掉。
                var run = cleanup.lockRun(key);
                if (run != null && !run.expired() && run.lastActivityAt().isBefore(cutoff)) {
                    cleanup.deleteReceipts(key);
                    cleanup.expireRun(key);
                }
            });
        }
        var ids = failures.previewExpired(cutoff, batchSize);
        if (!ids.isEmpty()) failures.deleteExpired(cutoff, ids);
    }

    private Instant cutoff() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).minus(AUDIT_RETENTION_DAYS, ChronoUnit.DAYS);
    }

    private static RetentionCandidates candidates(int found, int batchSize) {
        return new RetentionCandidates(Math.min(found, batchSize), found > batchSize);
    }
}
