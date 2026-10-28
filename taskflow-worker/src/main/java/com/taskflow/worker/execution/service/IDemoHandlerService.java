package com.taskflow.worker.execution.service;

import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.model.ExecutionContext;

public interface IDemoHandlerService {
    Set<String> keys();
    String execute(Snapshot snapshot, ExecutionContext context) throws InterruptedException;
}
