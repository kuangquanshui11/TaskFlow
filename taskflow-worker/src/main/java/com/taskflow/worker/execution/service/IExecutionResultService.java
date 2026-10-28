package com.taskflow.worker.execution.service;

import com.taskflow.worker.execution.model.ExecutionCompletion;

public interface IExecutionResultService {
    /** 有限重试同一份结果；失败时记录诊断，不重新执行任何业务动作。 */
    boolean save(ExecutionCompletion completion);
}
