package com.taskflow.worker.execution.client;

import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.remote.client.SchedulerLeaseRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SchedulerLeaseClient {
    private final SchedulerLeaseRemote remote;
    private final ExecutionProperties properties;
    private final JsonMapper json;
    public SchedulerLeaseClient(SchedulerLeaseRemote remote, ExecutionProperties properties, JsonMapper json) {
        this.remote = remote; this.properties = properties; this.json = json;
    }
    public LeaseAcknowledgement updateLease(LeaseRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Lease request inside SQL transaction");
        if (properties.reportToken().isBlank()) throw new ExecutionException(401, "LEASE_REJECTED", "未配置续租身份");
        try {
            return remote.updateLease(request, RemoteErrors.headers(properties.reportToken(), request.runId().toString()));
        } catch (RuntimeException failure) {
            int status = RemoteErrors.status(failure);
            if (status == 409 && cancelled(failure))
                throw new ExecutionException(409, "RUN_CANCEL_REQUESTED", "运行执行资格已取消");
            if (status >= 400 && status < 500 && status != 408 && status != 429)
                throw new ExecutionException(status, "LEASE_REJECTED", "Scheduler 拒绝执行租约");
            throw failure;
        }
    }

    private boolean cancelled(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof feign.FeignException remote) {
                try {
                    var body = json.readTree(remote.contentUTF8());
                    return body != null && "RUN_CANCEL_REQUESTED".equals(body.path("code").asString());
                } catch (tools.jackson.core.JacksonException invalid) {
                    return false;
                }
            }
        }
        return false;
    }
}
