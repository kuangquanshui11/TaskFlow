package com.taskflow.admin.reconciliation.service;

import java.util.UUID;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.AutomaticStatus;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.DifferencePage;
import org.springframework.security.access.prepost.PreAuthorize;

public interface IAutomaticReconciliationService {
    void scanNext();

    @PreAuthorize("hasAuthority('job:read')")
    AutomaticStatus selectLatest();

    @PreAuthorize("hasAuthority('job:read')")
    DifferencePage selectDifferences(UUID id, long afterJobId, int batchSize);
}
