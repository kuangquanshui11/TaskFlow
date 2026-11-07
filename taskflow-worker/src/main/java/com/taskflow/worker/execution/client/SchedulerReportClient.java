package com.taskflow.worker.execution.client;

import com.taskflow.worker.remote.client.SchedulerReportRemote;
import com.taskflow.worker.remote.model.RemoteErrors;
import com.taskflow.worker.execution.config.ExecutionProperties;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Result;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SchedulerReportClient {
    public enum Outcome { ACKNOWLEDGED, STALE, UNCONFIRMED }

    private final ExecutionProperties properties;
    private final JsonMapper json;
    private final SchedulerReportRemote remote;
    public SchedulerReportClient(ExecutionProperties properties, JsonMapper json,SchedulerReportRemote remote) {
        this.properties = properties; this.json = json;
        this.remote=remote;
    }
    public Outcome report(Result result) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Report inside SQL transaction");
        if (properties.reportToken().isBlank()) return Outcome.UNCONFIRMED;
        try {
            remote.report(result,RemoteErrors.headers(properties.reportToken(),result.runId().toString()));
            return Outcome.ACKNOWLEDGED;
        } catch (RuntimeException error) {
            // 409 表示租约/终态已变化，继续重报也不能覆盖 Scheduler 的权威结果。
            return RemoteErrors.status(error) == 409 ? Outcome.STALE : Outcome.UNCONFIRMED;
        }
    }
}
