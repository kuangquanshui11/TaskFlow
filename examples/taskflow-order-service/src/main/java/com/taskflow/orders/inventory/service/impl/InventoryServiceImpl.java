package com.taskflow.orders.inventory.service.impl;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.config.OrderOperationsProperties;
import com.taskflow.orders.inventory.mapper.*;
import com.taskflow.orders.inventory.model.Stock;
import com.taskflow.orders.inventory.service.IInventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryServiceImpl implements IInventoryService {
    private final InventoryMapper mapper;
    private final StockOutboxFailureMapper failures;
    private final StockCache cache;
    private final OrderProperties properties;
    private final OrderOperationsProperties operations;
    private final TransactionTemplate transactions;

    public InventoryServiceImpl(InventoryMapper mapper, StockOutboxFailureMapper failures,
                                StockCache cache, OrderProperties properties, OrderOperationsProperties operations,
                                PlatformTransactionManager manager) {
        this.mapper=mapper; this.failures=failures; this.cache=cache; this.properties=properties;
        this.operations = operations;
        this.transactions = new TransactionTemplate(manager);
    }
    @Override @Transactional public Stock insert(long skuId, long available) {
        if (skuId<=0 || available<0 || available>1000000000L) throw new IllegalArgumentException("Invalid stock");
        mapper.insert(skuId, available); changed(skuId);
        return mapper.find(skuId);
    }
    @Override public Stock select(long skuId) {
        if (properties.cacheEnabled()) try { var value=cache.get(skuId); if(value!=null) return value; }
        catch (RuntimeException unavailable) { /* 查询缓存故障不影响数据库读写。 */ }
        var stock=mapper.find(skuId);
        if(stock==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SKU_NOT_FOUND");
        if(properties.cacheEnabled()) try { cache.put(stock,properties.cacheTtl()); } catch(RuntimeException unavailable) { }
        return stock;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void hold(String id,long skuId,int quantity,String requestId) {
        if(quantity<=0) throw new IllegalArgumentException("Quantity must be positive");
        if(mapper.hold(skuId,quantity)!=1) {
            if(mapper.find(skuId)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SKU_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"INSUFFICIENT_STOCK");
        }
        mapper.reserve(id,skuId,quantity); ledger(id,skuId,quantity,"HELD",requestId); changed(skuId);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void settle(String id,long skuId,int quantity,boolean release,String requestId) {
        // 调用方先锁订单，只有 HELD 能转成一个终态；不一致必须回滚，不能吞掉库存更新失败。
        if(mapper.transition(id,release?"RELEASED":"CONSUMED")!=1 || mapper.settle(skuId,quantity,release?quantity:0)!=1)
            throw new IllegalStateException("Reservation/stock invariant violated");
        ledger(id,skuId,quantity,release?"RELEASED":"CONSUMED",requestId); changed(skuId);
    }
    private void ledger(String id,long skuId,int quantity,String operation,String requestId) {
        mapper.ledger(UUID.randomUUID().toString(),id,skuId,operation,quantity,requestId,Instant.now());
    }
    private void changed(long skuId) {
        mapper.enqueue(UUID.randomUUID().toString(),skuId,Instant.now());
        if(properties.cacheEnabled()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { try { cache.invalidate(skuId); } catch(RuntimeException unavailable) { } }
        });
    }
    @Override @Scheduled(fixedDelayString="${order.outbox-delay}")
    public void publishPending() {
        if (!properties.cacheEnabled() && !operations.enabled()) return;
        int maxAttempts = properties.outboxMaxAttempts();
        for (var row : mapper.exhaustedWithoutDiagnosis(maxAttempts)) {
            var now = Instant.now();
            // 旧版本没有保存失败时间和错误类别，升级时仅记录发现时间。
            recordFailure(row, row.attempts(), maxAttempts, now, null, "PREVIOUS_FAILURE_UNKNOWN");
        }
        if (!properties.cacheEnabled()) return;

        for (var row : mapper.pending(Instant.now(), maxAttempts)) {
            var stock = mapper.find(row.skuId());
            if (stock == null) {
                var now = Instant.now();
                recordFailure(row, row.attempts() + 1, maxAttempts, now, now, "SKU_NOT_FOUND");
                continue;
            }
            try {
                cache.put(stock, properties.cacheTtl());
            } catch(RuntimeException unavailable) {
                var now = Instant.now();
                recordFailure(row, row.attempts() + 1, maxAttempts, now, now, "CACHE_WRITE_FAILED");
                // Redis 故障时一次只尝试一条，限制阻塞。
                break;
            }
            transactions.executeWithoutResult(status -> {
                // Redis 在事务外执行。旧轮次或旧尝试的结果不能关闭当前待投递行。
                if (mapper.published(row) == 1) {
                    var now = Instant.now();
                    mapper.recordPublished(row.id(), row.skuId(), now);
                    failures.resolve(row.id(), now);
                }
            });
        }
    }

    private void recordFailure(InventoryMapper.Pending row, int attempts, int maxAttempts,
                               Instant now, Instant failedAt, String errorCode) {
        boolean exhausted = attempts >= maxAttempts;
        String state = exhausted ? "EXHAUSTED" : "PENDING";
        var next = now.plus(properties.outboxRetryDelay());
        transactions.executeWithoutResult(status -> {
            if (mapper.defer(row, attempts, next, state) == 1 && exhausted) {
                failures.insert(row, attempts, now, failedAt, errorCode);
            }
        });
    }
}
