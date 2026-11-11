package com.taskflow.orders.cleanup.service;

import com.taskflow.orders.cleanup.dto.CleanupProtocol.RetentionPreview;

public interface ICleanupRetentionService {
    RetentionPreview preview();
    void prune();
}
