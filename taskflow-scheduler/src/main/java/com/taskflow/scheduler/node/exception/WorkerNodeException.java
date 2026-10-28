package com.taskflow.scheduler.node.exception;

public class WorkerNodeException extends RuntimeException {
    private final int status;
    private final String code;
    public WorkerNodeException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
