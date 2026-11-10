package com.taskflow.orders.inventory.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Outcome;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Request;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Result;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Status;
import com.taskflow.orders.inventory.dto.InventorySyncProtocol.Stock;
import com.taskflow.orders.inventory.mapper.InventorySyncMapper;
import com.taskflow.orders.inventory.model.InventorySnapshot;
import com.taskflow.orders.inventory.model.InventorySnapshot.Item;
import com.taskflow.orders.inventory.service.IInventorySnapshotSource;
import com.taskflow.orders.inventory.service.IInventorySyncService;
import jakarta.validation.Validator;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
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
    private final Validator validator;

    public InventorySyncServiceImpl(InventorySyncMapper mapper, IInventorySnapshotSource source,
                                    PlatformTransactionManager manager, Validator validator) {
        this.mapper = mapper;
        this.source = source;
        this.transactions = new TransactionTemplate(manager);
        this.validator = validator;
    }

    @Override
    public Result insert(Request request) {
        var saved = savedResult(request);
        if (saved != null) return saved;

        try {
            // 远程读取、校验和摘要在事务外进行，持有仓库锁后再比较已应用版本。
            var snapshot = source.fetch(request.warehouseId());
            var items = validateAndSort(request.warehouseId(), snapshot);
            var fingerprint = fingerprint(items);
            return transactions.execute(status -> apply(request, snapshot.sourceVersion(), items, fingerprint));
        } catch (DuplicateKeyException competingRequest) {
            // 不同仓库抢到同一个请求键时，失败事务的镜像写入已回滚。
            saved = savedResult(request);
            if (saved != null) return saved;
            throw competingRequest;
        } catch (ResponseStatusException failure) {
            if (!"IDEMPOTENCY_CONFLICT".equals(failure.getReason())) recordFailure(request, failure.getReason());
            throw failure;
        }
    }

    private List<Item> validateAndSort(long warehouseId, InventorySnapshot snapshot) {
        var skuIds = new HashSet<Long>();
        if (snapshot == null || !validator.validate(snapshot).isEmpty()
                || snapshot.items().stream().anyMatch(item -> !skuIds.add(item.skuId()))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_INVENTORY_SNAPSHOT");
        }
        if (snapshot.warehouseId() != warehouseId) {
            if (source.replacesMissingStock())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_INVENTORY_SNAPSHOT");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND");
        }
        return snapshot.items().stream().sorted(Comparator.comparingLong(Item::skuId)).toList();
    }

    private Result apply(Request request, long sourceVersion, List<Item> items, String fingerprint) {
        String sourceId = source.sourceId();
        long warehouseId = request.warehouseId();
        mapper.ensureWarehouse(sourceId, warehouseId);
        var applied = mapper.lockWarehouse(sourceId, warehouseId);
        var saved = savedResult(request);
        if (saved != null) return saved;

        Outcome outcome;
        int updatedCount = 0;
        if (sourceVersion > applied.sourceVersion()) {
            if (source.replacesMissingStock()) updatedCount = mapper.removeMissingStock(sourceId, warehouseId, items);
            if (!items.isEmpty()) mapper.upsertStock(sourceId, warehouseId, sourceVersion, items);
            updatedCount += items.size();
            outcome = Outcome.APPLIED;
        } else if (sourceVersion == applied.sourceVersion()) {
            // V7 前的样例按每次传入的 SKU 标记版本，保留项可从同版本集合中排除。
            String existing = applied.fingerprint() == null
                    ? fingerprint(mapper.versionItems(sourceId, warehouseId, sourceVersion)) : applied.fingerprint();
            if (!existing.equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "INVENTORY_VERSION_CONFLICT");
            }
            outcome = Outcome.UNCHANGED;
        } else {
            outcome = Outcome.STALE;
        }
        if (outcome != Outcome.STALE) mapper.confirmVersion(sourceId, warehouseId, sourceVersion, fingerprint);
        var result = new Result(UUID.randomUUID().toString(), warehouseId, sourceVersion, outcome, updatedCount, sourceId);
        mapper.insertBatch(request.requestKey(), result);
        return result;
    }

    private String fingerprint(List<Item> items) {
        var canonical = new StringBuilder();
        for (var item : items) canonical.append(item.skuId()).append(':').append(item.quantity()).append('\n');
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private void recordFailure(Request request, String code) {
        try {
            // 失败诊断独立于镜像事务，版本冲突时镜像回滚后仍可查询原因。
            mapper.recordFailure(source.sourceId(), request.warehouseId(), code, request.requestKey());
        } catch (DataAccessException diagnosticFailure) {
            LoggerFactory.getLogger(getClass()).warn("Inventory sync diagnostic write failed: warehouseId={}, code={}",
                    request.warehouseId(), code);
        }
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
        var stock = mapper.stock(source.sourceId(), warehouseId, skuId);
        if (stock == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVENTORY_SNAPSHOT_NOT_FOUND");
        return stock;
    }

    @Override
    public Status selectStatus(long warehouseId) {
        var status = mapper.status(source.sourceId(), warehouseId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVENTORY_SNAPSHOT_NOT_FOUND");
        return status;
    }
}
