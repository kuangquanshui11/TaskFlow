package com.taskflow.admin.job.exception;

public class JobException extends RuntimeException {
    public enum Kind { INVALID, NOT_FOUND, CONFLICT }

    private final Kind kind;

    public JobException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static JobException invalid(String message) {
        return new JobException(Kind.INVALID, message);
    }
}
