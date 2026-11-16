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

    public String code() {
        return switch (kind) {
            case INVALID -> "INVALID_ARGUMENT";
            case NOT_FOUND -> "JOB_NOT_FOUND";
            case CONFLICT -> "JOB_VERSION_CONFLICT";
        };
    }

    public static JobException invalid(String message) {
        return new JobException(Kind.INVALID, message);
    }
}
