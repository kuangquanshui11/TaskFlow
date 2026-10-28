package com.taskflow.admin.run.exception;

public class RunException extends RuntimeException {
    private final int status;
    private final String code;
    public RunException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
