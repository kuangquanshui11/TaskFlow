package com.taskflow.scheduler.execution.client;

import com.taskflow.scheduler.remote.client.WorkerDispatchRemote;
import com.taskflow.scheduler.remote.model.RemoteErrors;
import com.taskflow.scheduler.execution.config.RunProperties;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkerClient {
    private final RunProperties properties;
    private final WorkerDispatchRemote remote;
    private final JsonMapper json;
    public WorkerClient(RunProperties properties, JsonMapper json,WorkerDispatchRemote remote) {
        this.properties = properties; this.json = json;
        this.remote=remote;
    }
    public void dispatch(Dispatch command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dispatch inside SQL transaction");
        if (properties.dispatchToken().isBlank()) throw new RunException(503, "SERVICE_NOT_CONFIGURED", "Worker 派发密钥未配置");
        String response;
        try {
            response=remote.insert(command,RemoteErrors.headers(properties.dispatchToken(),command.runId().toString()));
        } catch (RuntimeException failed) {
            throw new RunException(RemoteErrors.status(failed),"WORKER_REJECTED","Worker 未确认接收");
        }
        try {
            var accepted = json.readValue(response, Accepted.class);
            if (accepted==null || !command.attemptId().equals(accepted.attemptId())) throw new IllegalArgumentException("Mismatched attempt");
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) {
            throw new RunException(503,"WORKER_PROTOCOL_ERROR","Worker 响应不匹配");
        }
    }
}
