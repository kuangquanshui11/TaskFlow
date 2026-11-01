package com.taskflow.worker.execution.exception;

public class HandlerFailureException extends RuntimeException {
    private final boolean retryable;
    public HandlerFailureException(String message, boolean retryable) { super(message); this.retryable=retryable; }
    public boolean retryable() { return retryable; }
}
