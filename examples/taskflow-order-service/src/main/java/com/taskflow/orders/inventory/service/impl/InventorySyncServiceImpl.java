package com.taskflow.orders.inventory.service.impl;

import java.util.UUID;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Outcome;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.mapper.InventorySyncMapper;
import com.taskflow.orders.inventory.model.InventorySnapshot;
import com.taskflow.orders.inventory.service.IInventorySnapshotSource;
import com.taskflow.orders.inventory.service.IInventorySyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "order.inventory-sync.enabled", havingValue = "true")
public class InventorySyncServiceImpl implements IInventorySyncService {
    private final InventorySyncMapper mapper;
    private final IInventorySnapshotSource source;
    private final TransactionTemplate transactions;

    public InventorySyncServiceImpl(InventorySyncMapper mapper, IInventorySnapshotSource source,
                                    PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.source = source;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Result insert(Request request) {
        var saved = savedResult(request);
        if (saved != null) return saved;

        // 从源读取可慢于其他同步请求。版本比较必须在取得仓库行锁后进行。
        var snapshot = source.fetch(request.warehouseId());
        try {
            return transactions.execute(status -> apply(request, snapshot));
        } catch (DuplicateKeyException competingRequest) {
            // 不同仓库抢到同一个请求键时，失败事务的镜像写入已回滚。
            saved = savedResult(request);
            if (saved != null) return saved;
            throw competingRequest;
        }
    }

    private Result apply(Request request, InventorySnapshot snapshot) {
        long warehouseId = request.warehouseId();
        mapper.ensureWarehouse(warehouseId);
        long appliedVersion = mapper.lockWarehouse(warehouseId);
        var saved = savedResult(request);
        if (saved != null) return saved;

        long sourceVersion = snapshot.sourceVersion();
        Outcome outcome;
        int updatedCount = 0;
        if (sourceVersion > appliedVersion) {
            if (!snapshot.items().isEmpty()) mapper.upsertStock(warehouseId, sourceVersion, snapshot.items());
            mapper.updateVersion(warehouseId, sourceVersion);
            outcome = Outcome.APPLIED;
            updatedCount = snapshot.items().size();
        } else {
            outcome = sourceVersion == appliedVersion ? Outcome.UNCHANGED : Outcome.STALE;
        }
        var result = new Result(UUID.randomUUID().toString(), warehouseId, sourceVersion, outcome, updatedCount);
        mapper.insertBatch(request.requestKey(), result);
        return result;
    }

    private Result savedResult(Request request) {
        var saved = mapper.batch(request.requestKey());
        if (saved != null && saved.warehouseId() != request.warehouseId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }
        return saved;
    }

    @Override
    public Stock select(long warehouseId, long skuId) {
        var stock = mapper.stock(warehouseId, skuId);
        if (stock == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVENTORY_SNAPSHOT_NOT_FOUND");
        return stock;
    }
}
