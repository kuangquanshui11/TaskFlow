package com.taskflow.worker.execution.exception;

public class ExecutionCancelledException extends RuntimeException {
    private final String code;
    public ExecutionCancelledException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
