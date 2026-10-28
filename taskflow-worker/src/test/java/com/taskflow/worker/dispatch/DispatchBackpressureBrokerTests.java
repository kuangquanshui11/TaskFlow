package com.taskflow.worker.dispatch;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.taskflow.worker.dispatch.config.DispatchProperties;
import com.taskflow.worker.dispatch.dto.DispatchEvent;
import com.taskflow.worker.dispatch.service.IDispatchConsumerService;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.service.IExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// 仅由 test-mq.ps1 在自己创建的随机 vhost 中启用，不连接业务队列或业务数据库。
@EnabledIfEnvironmentVariable(named = "TASKFLOW_TEST_MQ_VHOST", matches = "^taskflow_it_[a-f0-9]{32}$")
@SpringBootTest
@ActiveProfiles({"test", "test-backpressure-mq"})
@ContextConfiguration(initializers = com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class DispatchBackpressureBrokerTests {
    @Autowired IDispatchConsumerService consumer;
    @Autowired IExecutionService executions;
    @Autowired SimpleMessageListenerContainer dispatchListener;
    @Autowired RabbitTemplate rabbit;
    @Autowired DispatchProperties dispatch;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean ExecutionMapper mapper;

    @Test void realBrokerKeepsBusyDeliveryThenAcceptsOriginalAndCleansExpiredDeliveryWithoutDeadLetters() throws Exception {
        var filler = command(Instant.now().plusSeconds(60));
        executions.insert(filler);
        for (int i = 0; i < 3; i++) executions.insert(command(Instant.now().plusSeconds(60)));
        var incoming = command(Instant.now().plusSeconds(60));
        var waiting = new CountDownLatch(8);
        var completed = new LinkedBlockingQueue<Boolean>();
        doAnswer(call -> { waiting.countDown(); return call.callRealMethod(); }).when(mapper).pendingCount();
        dispatchListener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            consumer.consume(message, channel);
            completed.add(true);
        });
        try {
            dispatchListener.start();
            publish(incoming);
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).isEmpty();
            assertThat(mapper.find(incoming.attemptId())).isEmpty();
            assertThat(rabbit.receive(dispatch.deadLetterQueue())).isNull();
            jdbc.update("DELETE FROM worker_execution WHERE attempt_id=?", filler.attemptId().toString());
            assertThat(completed.poll(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.find(incoming.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
            publish(incoming);
            assertThat(completed.poll(10, TimeUnit.SECONDS)).isTrue();
            var expired = command(Instant.now().minusSeconds(1));
            publish(expired);
            assertThat(completed.poll(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.find(expired.attemptId())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_execution", Long.class)).isEqualTo(4);
            assertThat(rabbit.receive(dispatch.deadLetterQueue())).isNull();
        } finally { dispatchListener.stop(); }
    }

    private Dispatch command(Instant expiresAt) {
        return new Dispatch(UUID.randomUUID(), UUID.randomUUID(), 1, 1, expiresAt,
                new Snapshot(7, 2, "mq-backpressure", "demoSuccess", json.readTree("{}"), 30, 1, 5,
                        "MANUAL", null, "UTC", "SKIP", false, null));
    }
    private void publish(Dispatch command) {
        rabbit.send(dispatch.exchange(), dispatch.routingKey(), MessageBuilder
                .withBody(json.writeValueAsBytes(new DispatchEvent(1, command.attemptId(), command)))
                .setMessageId(command.attemptId().toString()).setType("taskflow.dispatch.v1")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build());
    }
}
