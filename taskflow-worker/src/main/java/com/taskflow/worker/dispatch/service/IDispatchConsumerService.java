package com.taskflow.worker.dispatch.service;

import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;

public interface IDispatchConsumerService {
    void consume(Message message, Channel channel) throws IOException;
}
