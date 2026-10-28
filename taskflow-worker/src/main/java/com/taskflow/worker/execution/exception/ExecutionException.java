package com.taskflow.worker.execution.exception;

public class ExecutionException extends RuntimeException {
    private final int status;
    private final String code;
    public ExecutionException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
