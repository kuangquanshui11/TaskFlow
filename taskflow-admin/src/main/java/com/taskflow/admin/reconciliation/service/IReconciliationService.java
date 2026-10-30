package com.taskflow.admin.reconciliation.service;

import java.util.UUID;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import org.springframework.security.access.prepost.PreAuthorize;

public interface IReconciliationService {
    @PreAuthorize("hasAuthority('job-sync:reconcile')")
    Scan insert(UUID requestId, long afterJobId, int batchSize);
    @PreAuthorize("hasAuthority('job:read')")
    Scan selectById(UUID requestId);
    @PreAuthorize("hasAuthority('job-sync:repair')")
    Repair repair(UUID requestId, UUID checkId);
    @PreAuthorize("hasAuthority('job:read')")
    Repair selectRepair(UUID requestId);
}
