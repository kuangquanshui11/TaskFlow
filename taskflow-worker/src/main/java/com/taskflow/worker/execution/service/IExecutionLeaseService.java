package com.taskflow.worker.execution.service;

import com.taskflow.worker.execution.dto.ExecutionProtocol.Dispatch;
import com.taskflow.worker.execution.model.ExecutionOwner;
import com.taskflow.worker.execution.model.ExecutionContext;

public interface IExecutionLeaseService {
    ExecutionContext open(Dispatch dispatch);
    ExecutionOwner owner();
    void close(ExecutionContext context);
    long watchdogIntervalMillis();
}
