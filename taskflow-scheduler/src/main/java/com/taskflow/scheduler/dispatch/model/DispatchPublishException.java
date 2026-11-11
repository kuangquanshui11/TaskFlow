package com.taskflow.scheduler.dispatch.model;

public final class DispatchPublishException extends IllegalStateException {
    public enum Reason { MQ_UNROUTABLE, MQ_BROKER_NACK, MQ_CONFIRM_TIMEOUT, MQ_PUBLISH_UNCONFIRMED }

    private final Reason reason;

    public DispatchPublishException(Reason reason) {
        this(reason, null);
    }

    public DispatchPublishException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
