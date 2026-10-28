package com.taskflow.scheduler.dispatch.service;

import com.taskflow.scheduler.dispatch.model.OutboxEvent;

public interface IDispatchPublisher {
    void publish(OutboxEvent event);
}
