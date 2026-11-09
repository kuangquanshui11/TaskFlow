package com.taskflow.worker.cleanup.service.impl;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.ITaskHandler;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.ExpiredDataCleanupRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(name = "taskflow.expired-data-cleanup.enabled", havingValue = "true")
public class ExpiredDataCleanupHandlerServiceImpl implements ITaskHandler {
    private final ExpiredDataCleanupRemote remote;
    private final OrderHandlerProperties order;
    private final String token;

    public ExpiredDataCleanupHandlerServiceImpl(ExpiredDataCleanupRemote remote, OrderHandlerProperties order,
            @Value("${taskflow.expired-data-cleanup.token}") String token) {
        if (token == null || token.isBlank() || token.equals(order.token())) {
            throw new IllegalStateException("Cleanup requires a separate token");
        }
        this.remote = remote;
        this.order = order;
        this.token = token;
    }

    @Override
    public Set<String> keys() {
        return Set.of("expiredDataCleanup");
    }

    @Override
    public String execute(Snapshot snapshot, ExecutionContext context) {
        var payload = snapshot.payload();
        if (payload != null && !payload.isNull() && !payload.isObject()) {
            throw new IllegalArgumentException("Cleanup payload must be an object");
        }
        int retentionDays = parameter(payload, "retentionDays", 90, 90, 3650);
        int batchSize = parameter(payload, "batchSize", 500, 1, 500);
        int maxBatches = parameter(payload, "maxBatches", 10, 1, 10);
        var dryRun = payload == null ? null : payload.get("dryRun");
        if (dryRun != null && !dryRun.isBoolean()) throw new IllegalArgumentException("Invalid dryRun");

        var key = context.businessIdempotencyKey("expired-data-cleanup");
        var headers = RemoteErrors.headers(token, key);
        if (dryRun == null || dryRun.booleanValue()) {
            checkBudget(context);
            var preview = call(() -> remote.select(retentionDays, batchSize, headers));
            context.checkActive();
            if (preview == null || preview.cutoff() == null || preview.eligibleCount() < 0
                    || preview.eligibleCount() > batchSize || (preview.hasMore() && preview.eligibleCount() != batchSize)) {
                throw invalidResponse();
            }
            return "mode=PREVIEW, cutoff=" + preview.cutoff() + ", eligibleCount=" + preview.eligibleCount()
                    + ", deletedCount=0, hasMore=" + preview.hasMore();
        }
        return delete(context, key, headers, retentionDays, batchSize, maxBatches);
    }

    private String delete(ExecutionContext context, String key, Map<String, String> headers, int retentionDays,
                          int batchSize, int maxBatches) {
        Instant cutoff = null;
        int deleted = 0;
        int batches = 0;
        boolean hasMore = true;
        while (hasMore && batches < maxBatches) {
            checkBudget(context);
            var request = new ExpiredDataCleanupRemote.Request(key, retentionDays, batchSize, batches + 1);
            var batch = call(() -> remote.delete(request, headers));
            context.checkActive();
            if (batch == null || batch.cutoff() == null || batch.batchNumber() != request.batchNumber()
                    || (cutoff != null && !cutoff.equals(batch.cutoff())) || batch.deletedCount() < 0
                    || batch.deletedCount() > batchSize || (batch.hasMore() && batch.deletedCount() != batchSize)) {
                throw invalidResponse();
            }
            cutoff = batch.cutoff();
            deleted += batch.deletedCount();
            batches++;
            hasMore = batch.hasMore();
        }
        return "mode=DELETE, cutoff=" + cutoff + ", deletedCount=" + deleted
                + ", batches=" + batches + ", hasMore=" + hasMore;
    }

    private void checkBudget(ExecutionContext context) {
        context.checkActive();
        if (Instant.now().plus(order.connectTimeout()).plus(order.requestTimeout()).isAfter(context.executionDeadline())) {
            throw new HandlerFailureException("清理请求剩余执行预算不足", true);
        }
    }

    private static int parameter(JsonNode payload, String name, int fallback, int min, int max) {
        var value = payload == null ? null : payload.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value.intValue();
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RuntimeException failure) {
            int status = RemoteErrors.status(failure);
            throw new HandlerFailureException("过期数据清理调用失败，HTTP=" + status,
                    status == 408 || status == 429 || status >= 500);
        }
    }

    private static HandlerFailureException invalidResponse() {
        return new HandlerFailureException("过期数据清理响应不合法", false);
    }
}
