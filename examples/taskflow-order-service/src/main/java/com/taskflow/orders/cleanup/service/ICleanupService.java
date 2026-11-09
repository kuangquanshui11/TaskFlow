package com.taskflow.orders.cleanup.service;

import com.taskflow.orders.cleanup.dto.CleanupProtocol.Batch;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Preview;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Request;

public interface ICleanupService {
    Preview select(int retentionDays, int batchSize);
    Batch delete(Request request);
}
