package com.taskflow.worker.node.exception;

public class NodeClientException extends RuntimeException {
    private final int status;
    public NodeClientException(int status,String code) { super(code); this.status=status; }
    public int status() { return status; }
}
