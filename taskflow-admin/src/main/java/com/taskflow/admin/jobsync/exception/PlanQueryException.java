package com.taskflow.admin.jobsync.exception;

public class PlanQueryException extends RuntimeException {
    private final int status;
    private final String code;
    public PlanQueryException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
