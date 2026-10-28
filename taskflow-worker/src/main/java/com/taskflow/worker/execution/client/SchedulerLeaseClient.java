package com.taskflow.worker.execution.client;

import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.remote.client.SchedulerLeaseRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SchedulerLeaseClient {
    private final SchedulerLeaseRemote remote;
    private final ExecutionProperties properties;
    public SchedulerLeaseClient(SchedulerLeaseRemote remote, ExecutionProperties properties) {
        this.remote = remote; this.properties = properties;
    }
    public LeaseAcknowledgement updateLease(LeaseRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Lease request inside SQL transaction");
        if (properties.reportToken().isBlank()) throw new ExecutionException(401, "LEASE_REJECTED", "未配置续租身份");
        try {
            return remote.updateLease(request, RemoteErrors.headers(properties.reportToken(), request.runId().toString()));
        } catch (RuntimeException failure) {
            int status = RemoteErrors.status(failure);
            if (status >= 400 && status < 500 && status != 408 && status != 429)
                throw new ExecutionException(status, "LEASE_REJECTED", "Scheduler 拒绝执行租约");
            throw failure;
        }
    }
}
