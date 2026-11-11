package com.taskflow.orders.inventory.service;

import java.util.List;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.Failure;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryReceipt;
import com.taskflow.orders.inventory.dto.StockOutboxOperations.RetryRequest;

public interface IStockOutboxOperationsService {
    List<Failure> select(String eventId, long afterId, int limit, boolean includeResolved);
    RetryReceipt retry(String eventId, RetryRequest request);
}
