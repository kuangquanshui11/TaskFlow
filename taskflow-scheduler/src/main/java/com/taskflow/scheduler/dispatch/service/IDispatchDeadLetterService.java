package com.taskflow.scheduler.dispatch.service;

import org.springframework.amqp.core.Message;

public interface IDispatchDeadLetterService {
    void record(Message message);
}
