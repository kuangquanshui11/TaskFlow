package com.taskflow.worker.execution.service;

import com.taskflow.worker.execution.model.ExecutionCompletion;

public interface IExecutionResultService {
    enum Outcome { SAVED, DEFERRED, CONFLICT }

    Outcome save(ExecutionCompletion completion);
    Outcome recover(ExecutionCompletion completion);
}
