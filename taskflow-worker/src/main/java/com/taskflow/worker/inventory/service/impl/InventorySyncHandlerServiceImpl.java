package com.taskflow.worker.inventory.service.impl;

import java.time.Instant;
import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.HandlerFailureException;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.ITaskHandler;
import com.taskflow.worker.order.config.OrderHandlerProperties;
import com.taskflow.worker.remote.client.InventorySyncRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "taskflow.inventory-sync.enabled", havingValue = "true")
public class InventorySyncHandlerServiceImpl implements ITaskHandler {
    private final InventorySyncRemote remote;
    private final OrderHandlerProperties properties;

    public InventorySyncHandlerServiceImpl(InventorySyncRemote remote, OrderHandlerProperties properties) {
        this.remote = remote;
        this.properties = properties;
        if (properties.token() == null || properties.token().isBlank()) {
            throw new IllegalStateException("Inventory sync token required");
        }
    }

    @Override
    public Set<String> keys() { return Set.of("inventorySync"); }

    @Override
    public String execute(Snapshot snapshot, ExecutionContext context) {
        var payload = snapshot.payload();
        var warehouse = payload == null ? null : payload.get("warehouseId");
        if (warehouse == null || !warehouse.isIntegralNumber() || !warehouse.canConvertToLong()
                || warehouse.longValue() < 1) {
            throw new IllegalArgumentException("warehouseId must be a positive integer");
        }
        var input = new InventorySyncRemote.Request(context.businessIdempotencyKey("inventory-sync"), warehouse.longValue());
        context.checkActive();
        if (Instant.now().plus(properties.connectTimeout()).plus(properties.requestTimeout()).isAfter(context.executionDeadline())) {
            throw new HandlerFailureException("库存同步请求剩余执行预算不足", true);
        }
        InventorySyncRemote.Result result;
        try {
            result = remote.insert(input, RemoteErrors.headers(properties.token(), input.requestKey()));
        } catch (RuntimeException failure) {
            int status = RemoteErrors.status(failure);
            throw new HandlerFailureException("库存同步调用失败，HTTP=" + status, status == 408 || status == 429 || status >= 500);
        }
        context.checkActive();
        if (result == null || result.id() == null || !result.id().matches("[a-f0-9-]{36}")
                || result.warehouseId() != input.warehouseId() || result.sourceVersion() < 1
                || result.outcome() == null || !Set.of("APPLIED", "UNCHANGED", "STALE").contains(result.outcome())
                || result.updatedCount() < 0 || (!"APPLIED".equals(result.outcome()) && result.updatedCount() != 0)) {
            throw new HandlerFailureException("库存同步响应不合法", false);
        }
        return "batchId=" + result.id() + ", warehouseId=" + result.warehouseId() + ", sourceVersion="
                + result.sourceVersion() + ", outcome=" + result.outcome() + ", updatedCount=" + result.updatedCount();
    }
}
