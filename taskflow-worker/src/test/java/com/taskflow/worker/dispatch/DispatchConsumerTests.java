package com.taskflow.worker.dispatch;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import com.rabbitmq.client.Channel;
import com.taskflow.worker.dispatch.dto.DispatchEvent;
import com.taskflow.worker.dispatch.service.IDispatchConsumerService;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.service.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"taskflow.dispatch.transport=rabbitmq", "taskflow.dispatch.consume-retry-delay=1ms"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class DispatchConsumerTests {
    @Autowired IDispatchConsumerService consumer;
    @Autowired IExecutionService executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean SchedulerReportClient reports;
    @MockitoSpyBean IDemoHandlerService handlers;
    @MockitoSpyBean ExecutionMapper mapper;
    private Channel channel;
    @BeforeEach void clean() { jdbc.update("DELETE FROM worker_execution"); channel=mock(Channel.class); }

    @Test void ackHappensAfterInboxCommitAndDuplicateDoesNotExecuteAgain() throws Exception {
        var command = command(); var message=message(command);
        doAnswer(call -> {
            assertThat(mapper.find(command.attemptId())).isPresent();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(channel).basicAck(7,false);
        consumer.consume(message,channel); consumer.consume(message,channel);
        executions.executePending();
        await().untilAsserted(() -> assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED"));
        consumer.consume(message,channel); executions.executePending();
        assertThat(count()).isEqualTo(1);
        verify(handlers,times(1)).execute(any());
        verify(channel,times(3)).basicAck(7,false);
    }
    @Test void lostAckAfterCommitCanBeRedeliveredWithoutDuplicateExecution() throws Exception {
        var command=command(); var message=message(command);
        doThrow(new IOException("lost ACK")).doNothing().when(channel).basicAck(7,false);
        assertThatThrownBy(() -> consumer.consume(message,channel)).isInstanceOf(IOException.class);
        assertThat(count()).isEqualTo(1);
        consumer.consume(message,channel); executions.executePending();
        await().untilAsserted(() -> assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED"));
        verify(handlers,times(1)).execute(any());
    }
    @Test void malformedUnsupportedOrMismatchedEventsAreRejectedWithoutInboxWrite() throws Exception {
        var valid = message(command());
        consumer.consume(MessageBuilder.withBody("null".getBytes()).copyProperties(valid.getMessageProperties()).build(),channel);
        consumer.consume(MessageBuilder.withBody("{".getBytes()).copyProperties(valid.getMessageProperties()).build(),channel);
        var command=command();
        consumer.consume(MessageBuilder.withBody(json.writeValueAsBytes(new DispatchEvent(2,command.attemptId(),command)))
                .copyProperties(message(command).getMessageProperties()).build(),channel);
        var mismatch=message(command); mismatch.getMessageProperties().setMessageId(UUID.randomUUID().toString());
        consumer.consume(mismatch,channel);
        assertThat(count()).isZero(); verify(channel,times(4)).basicReject(7,false); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void conflictingDuplicateGoesToDeadLetterAndPreservesOriginal() throws Exception {
        var command=command(); consumer.consume(message(command),channel);
        var conflict=new Dispatch(command.attemptId(),UUID.randomUUID(),1,1,command.expiresAt(),command.snapshot());
        consumer.consume(message(conflict),channel);
        assertThat(count()).isEqualTo(1); verify(channel).basicReject(7,false);
        assertThat(mapper.find(command.attemptId()).orElseThrow().runId()).isEqualTo(command.runId());
    }
    @Test void transientDatabaseFailureRetriesBeforeAck() throws Exception {
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database unavailable")).doCallRealMethod()
                .when(mapper).insert(any(),any(),anyString(),anyString(),any());
        consumer.consume(message(command()),channel);
        assertThat(count()).isEqualTo(1); verify(channel).basicAck(7,false); verify(channel,never()).basicReject(anyLong(),anyBoolean());
    }
    @Test void persistentDatabaseFailureHasBoundedRetriesAndNoAck() throws Exception {
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
                .when(mapper).insert(any(),any(),anyString(),anyString(),any());
        consumer.consume(message(command()),channel);
        assertThat(count()).isZero(); verify(mapper,times(4)).insert(any(),any(),anyString(),anyString(),any());
        verify(channel).basicReject(7,false); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void expiredNewDispatchIsRejectedWithoutExecution() throws Exception {
        var original=command(); var expired=new Dispatch(original.attemptId(),original.runId(),1,1,Instant.now().minusSeconds(1),original.snapshot());
        consumer.consume(message(expired),channel);
        assertThat(count()).isZero(); verify(channel).basicReject(7,false); verify(handlers,never()).execute(any());
    }
    private Dispatch command() {
        return new Dispatch(UUID.randomUUID(),UUID.randomUUID(),1,1,Instant.now().plusSeconds(60),
                new Snapshot(7,2,"mq-test","demoSuccess",json.readTree("{}"),30,1,5,"MANUAL",null,"UTC","SKIP",false,null));
    }
    private Message message(Dispatch command) {
        return MessageBuilder.withBody(json.writeValueAsBytes(new DispatchEvent(1,command.attemptId(),command)))
                .setMessageId(command.attemptId().toString()).setType("taskflow.dispatch.v1").setDeliveryTag(7L).build();
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM worker_execution",Long.class); }
}
