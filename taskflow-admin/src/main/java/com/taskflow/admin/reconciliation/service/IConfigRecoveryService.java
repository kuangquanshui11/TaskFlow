package com.taskflow.admin.reconciliation.service;

import com.taskflow.admin.reconciliation.model.ConfigRecoveryRecords.*;
import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("hasAuthority('job-sync:recover')")
public interface IConfigRecoveryService {
    Bounds selectBounds();
    Batch restore(long afterJobId, long upperJobId, int batchSize);
}
