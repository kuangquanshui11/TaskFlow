package com.taskflow.worker.dispatch;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.rabbitmq.client.Channel;
import com.taskflow.worker.dispatch.dto.DispatchEvent;
import com.taskflow.worker.dispatch.service.IDispatchConsumerService;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
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
    @MockitoBean SchedulerLeaseClient leaseClient;
    @MockitoSpyBean IDemoHandlerService handlers;
    @MockitoSpyBean ExecutionMapper mapper;
    private Channel channel;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM worker_execution");
        when(leaseClient.updateLease(any())).thenAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            var now = Instant.now();
            var command = json.readValue(mapper.find(request.attemptId()).orElseThrow().dispatchJson(), Dispatch.class);
            return new LeaseAcknowledgement(request.attemptId(), request.runId(), request.leaseVersion(), request.workerId(),
                    request.sessionId(), now, command.expiresAt(), command.expiresAt().minusSeconds(10), command.expiresAt());
        });
        channel=mock(Channel.class); when(channel.isOpen()).thenReturn(true);
    }

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
        verify(handlers,times(1)).execute(any(), any());
        verify(channel,times(3)).basicAck(7,false);
    }
    @Test void lostAckAfterCommitCanBeRedeliveredWithoutDuplicateExecution() throws Exception {
        var command=command(); var message=message(command);
        doThrow(new IOException("lost ACK")).doNothing().when(channel).basicAck(7,false);
        assertThatThrownBy(() -> consumer.consume(message,channel)).isInstanceOf(IOException.class);
        assertThat(count()).isEqualTo(1);
        consumer.consume(message,channel); executions.executePending();
        await().untilAsserted(() -> assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED"));
        verify(handlers,times(1)).execute(any(), any());
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
    @Test void capacityWaitsNeitherConsumeNorResetDatabaseFailureBudget() throws Exception {
        var checks = new AtomicInteger();
        doAnswer(call -> {
            if (checks.incrementAndGet() % 9 == 0)
                throw new org.springframework.dao.TransientDataAccessResourceException("database unavailable");
            return 4L;
        }).when(mapper).pendingCount();
        consumer.consume(message(command()), channel);
        assertThat(checks.get()).isEqualTo(36);
        assertThat(count()).isZero();
        verify(channel).basicReject(7, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void fullWorkerWaitsBeyondFailureBudgetAndAcceptsAfterCapacityReturns() throws Exception {
        var accepted = command();
        executions.insert(accepted);
        for (int i = 0; i < 3; i++) executions.insert(command());
        var incoming = command();
        var waiting = new CountDownLatch(8);
        doAnswer(call -> { waiting.countDown(); return call.callRealMethod(); }).when(mapper).pendingCount();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var consumption = executor.submit(() -> { consumer.consume(message(incoming), channel); return null; });
            try {
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(consumption.isDone()).isFalse();
                assertThat(mapper.find(incoming.attemptId())).isEmpty();
                verify(channel, never()).basicAck(anyLong(), anyBoolean());
                verify(channel, never()).basicReject(anyLong(), anyBoolean());
                verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
                jdbc.update("DELETE FROM worker_execution WHERE attempt_id=?", accepted.attemptId().toString());
                consumption.get(5, TimeUnit.SECONDS);
                assertThat(count()).isEqualTo(4);
                assertThat(mapper.find(incoming.attemptId()).orElseThrow().runId()).isEqualTo(incoming.runId());
                verify(channel).basicAck(7, false);
                verify(channel, never()).basicReject(anyLong(), anyBoolean());
                verify(handlers, never()).execute(any(), any());
            } finally { consumption.cancel(true); }
        }
    }
    @Test void fullWorkerAcknowledgesExpiredDeliveryWithoutDeadLetterOrInboxWrite() throws Exception {
        for (int i = 0; i < 4; i++) executions.insert(command());
        var original = command();
        var incoming = new Dispatch(original.attemptId(), original.runId(), 1, 1,
                Instant.now().plusSeconds(1), original.snapshot());
        consumer.consume(message(incoming), channel);
        assertThat(count()).isEqualTo(4);
        assertThat(mapper.find(incoming.attemptId())).isEmpty();
        verify(mapper, atLeast(1)).pendingCount();
        verify(channel).basicAck(7, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(handlers, never()).execute(any(), any());
    }
    @Test void expiredNewDispatchIsAcknowledgedWithoutExecutionOrDeadLetter() throws Exception {
        var original=command(); var expired=new Dispatch(original.attemptId(),original.runId(),1,1,Instant.now().minusSeconds(1),original.snapshot());
        consumer.consume(message(expired),channel);
        assertThat(count()).isZero(); verify(channel).basicAck(7,false); verify(handlers,never()).execute(any(), any());
        verify(channel,never()).basicReject(anyLong(),anyBoolean());
    }
    @Test void closedChannelLeavesDeliveryToBrokerRecoveryWithoutInboxWrite() throws Exception {
        when(channel.isOpen()).thenReturn(false);
        consumer.consume(message(command()),channel);
        assertThat(count()).isZero(); verifyNoInteractions(handlers);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
        verify(channel,never()).basicReject(anyLong(),anyBoolean());
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void interruptedCapacityWaitReturnsDeliveryForRecoveryWithoutDeadLetter() throws Exception {
        doReturn(4L).when(mapper).pendingCount();
        try {
            Thread.currentThread().interrupt();
            consumer.consume(message(command()), channel);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(count()).isZero();
            verify(channel).basicNack(7, false, true);
            verify(channel, never()).basicReject(anyLong(), anyBoolean());
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
        } finally { Thread.interrupted(); }
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
