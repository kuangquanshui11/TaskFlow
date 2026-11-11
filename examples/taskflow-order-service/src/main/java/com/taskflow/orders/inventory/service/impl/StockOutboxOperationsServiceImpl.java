package com.taskflow.orders.inventory.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.taskflow.orders.config.OrderProperties;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.Failure;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryReceipt;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryRequest;
import com.taskflow.orders.inventory.mapper.StockOutboxFailureMapper;
import com.taskflow.orders.inventory.service.IStockOutboxOperationsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "order.operations.enabled", havingValue = "true")
public class StockOutboxOperationsServiceImpl implements IStockOutboxOperationsService {
    private final StockOutboxFailureMapper mapper;
    private final OrderProperties properties;

    public StockOutboxOperationsServiceImpl(StockOutboxFailureMapper mapper, OrderProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public List<Failure> select(String eventId, long afterId, int limit, boolean includeResolved) {
        return mapper.select(eventId, afterId, limit, includeResolved);
    }

    @Override
    @Transactional
    public RetryReceipt retry(String eventId, RetryRequest request) {
        // 发布、失败和补偿都先更新 Outbox，再更新诊断记录。
        var delivery = mapper.lockDelivery(eventId);
        long generation = request.generation();
        var failure = mapper.lockFailure(eventId, generation);
        if (failure == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_OUTBOX_FAILURE_NOT_FOUND");
        if (failure.retryRequestedAt() != null) {
            if (!failure.retryReason().equals(request.reason())) throw conflict("IDEMPOTENCY_CONFLICT");
            return receipt(failure.eventId(), generation, failure.retryRequestedAt(), failure.retryReason());
        }
        if (!properties.cacheEnabled()) throw conflict("STOCK_CACHE_DISABLED");
        if (failure.resolvedAt() != null || delivery == null || delivery.generation() != generation
                || !"EXHAUSTED".equals(delivery.deliveryState())) {
            throw conflict("STOCK_OUTBOX_FAILURE_NOT_CURRENT");
        }

        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (mapper.requeue(failure.eventId(), generation, now) != 1) throw conflict("STOCK_OUTBOX_FAILURE_NOT_CURRENT");
        mapper.saveRetry(failure.id(), now, request.reason());
        return receipt(failure.eventId(), generation, now, request.reason());
    }

    private static RetryReceipt receipt(String eventId, long generation, Instant now, String reason) {
        return new RetryReceipt(eventId, generation, generation + 1, now, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
