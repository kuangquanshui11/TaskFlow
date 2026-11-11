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
    public enum Outcome {
        ACKNOWLEDGED, STALE, UNCONFIRMED, REPORT_AUTH_FAILED, REPORT_REQUEST_REJECTED, REPORT_NOT_CONFIGURED,
        REPORT_REMOTE_FAILURE, REPORT_REMOTE_TIMEOUT, REPORT_RATE_LIMITED
    }

    private final ExecutionProperties properties;
    private final JsonMapper json;
    private final SchedulerReportRemote remote;
    public SchedulerReportClient(ExecutionProperties properties, JsonMapper json,SchedulerReportRemote remote) {
        this.properties = properties; this.json = json;
        this.remote=remote;
    }
    public Outcome report(Result result) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Report inside SQL transaction");
        if (properties.reportToken().isBlank()) return Outcome.REPORT_NOT_CONFIGURED;
        try {
            remote.report(result,RemoteErrors.headers(properties.reportToken(),result.runId().toString()));
            return Outcome.ACKNOWLEDGED;
        } catch (RuntimeException error) {
            // 409 表示租约/终态已变化，继续重报也不能覆盖 Scheduler 的权威结果。
            int status = RemoteErrors.status(error, 0);
            if (status == 409) return Outcome.STALE;
            if (status == 401 || status == 403) return Outcome.REPORT_AUTH_FAILED;
            if (status == 408) return Outcome.REPORT_REMOTE_TIMEOUT;
            if (status == 429) return Outcome.REPORT_RATE_LIMITED;
            if (status >= 500) return Outcome.REPORT_REMOTE_FAILURE;
            if (status >= 400) return Outcome.REPORT_REQUEST_REJECTED;
            return Outcome.UNCONFIRMED;
        }
    }

}
