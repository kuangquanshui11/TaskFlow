package com.taskflow.worker.execution.service;

import java.util.Set;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Dispatch;

public interface IDemoHandlerService {
    Set<String> keys();
    String execute(Dispatch request) throws InterruptedException;
}
