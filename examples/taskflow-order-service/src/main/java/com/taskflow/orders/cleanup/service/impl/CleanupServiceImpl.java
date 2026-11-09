package com.taskflow.orders.cleanup.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Batch;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Preview;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Request;
import com.taskflow.orders.cleanup.mapper.CleanupMapper;
import com.taskflow.orders.cleanup.service.ICleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "order.cleanup.enabled", havingValue = "true")
public class CleanupServiceImpl implements ICleanupService {
    private final CleanupMapper mapper;
    private final TransactionTemplate transactions;

    public CleanupServiceImpl(CleanupMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Preview select(int retentionDays, int batchSize) {
        var cutoff = cutoff(retentionDays);
        int found = mapper.preview(cutoff, batchSize + 1).size();
        return new Preview(cutoff, Math.min(found, batchSize), found > batchSize);
    }

    @Override
    public Batch delete(Request request) {
        if (request.batchNumber() == 1) {
            // 固定截止时间先提交，首批删除回滚后仍按原截止时间重试。
            transactions.executeWithoutResult(status -> mapper.bind(request.requestKey(),
                    cutoff(request.retentionDays()), request.retentionDays(), request.batchSize()));
        }
        return transactions.execute(status -> deleteBatch(request));
    }

    private Batch deleteBatch(Request request) {
        var key = request.requestKey();
        int number = request.batchNumber();
        var run = mapper.lockRun(key);
        if (run == null) throw conflict("CLEANUP_BATCH_OUT_OF_SEQUENCE");
        if (run.retentionDays() != request.retentionDays() || run.batchSize() != request.batchSize()) {
            throw conflict("IDEMPOTENCY_CONFLICT");
        }
        var saved = mapper.batch(key, number);
        if (saved != null) return saved;
        if (number > 1) {
            var previous = mapper.batch(key, number - 1);
            if (previous == null || !previous.hasMore()) throw conflict("CLEANUP_BATCH_OUT_OF_SEQUENCE");
        }

        var ids = mapper.lockExpired(run.cutoff(), run.batchSize() + 1);
        int count = Math.min(ids.size(), run.batchSize());
        int deleted = count == 0 ? 0 : mapper.delete(ids.subList(0, count));
        if (deleted != count) throw new IllegalStateException("Locked cleanup batch changed");
        var result = new Batch(run.cutoff(), number, deleted, ids.size() > count);
        mapper.save(key, result);
        return result;
    }

    private static Instant cutoff(int retentionDays) {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).minus(retentionDays, ChronoUnit.DAYS);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
