package com.taskflow.worker.node;

import java.time.Instant;
import java.util.*;
import com.taskflow.worker.node.client.WorkerNodeClient;
import com.taskflow.worker.node.dto.WorkerNodeProtocol.*;
import com.taskflow.worker.node.exception.NodeClientException;
import com.taskflow.worker.node.service.IWorkerRegistrationService;
import com.taskflow.worker.execution.service.IExecutionService;
import com.taskflow.worker.execution.model.ExecutionLoad;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkerRegistrationTests {
    @Autowired IWorkerRegistrationService registration;
    @MockitoBean WorkerNodeClient client;
    @MockitoSpyBean IExecutionService executions;
    @BeforeEach void setup() {
        when(client.register(any())).thenAnswer(call->{ Registration r=call.getArgument(0);return new Acknowledgement(r.workerId(),r.sessionId(),1,0,Instant.now().plusSeconds(30)); });
        when(client.heartbeat(any())).thenAnswer(call->{ Heartbeat h=call.getArgument(0);return new Acknowledgement(h.workerId(),h.sessionId(),h.generation(),h.sequence(),Instant.now().plusSeconds(30)); });
        doReturn(new ExecutionLoad(1,2)).when(executions).selectLoad();
    }
    @Test void registersCapabilitiesOnceAndReportsIncreasingSequencesOutsideTransactions() {
        doAnswer(call->{ assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Heartbeat h=call.getArgument(0);return new Acknowledgement(h.workerId(),h.sessionId(),1,h.sequence(),Instant.now().plusSeconds(30)); }).when(client).heartbeat(any());
        registration.pulse();registration.pulse();
        var registered=ArgumentCaptor.forClass(Registration.class);verify(client).register(registered.capture());
        assertThat(registered.getValue().handlerKeys()).contains("demoSuccess","demoSlow");
        assertThat(registered.getValue().dispatchTransport()).isEqualTo("http");assertThat(registered.getValue().dispatchQueue()).isNull();
        var beats=ArgumentCaptor.forClass(Heartbeat.class);verify(client,times(2)).heartbeat(beats.capture());
        assertThat(beats.getAllValues().stream().map(Heartbeat::sequence)).containsExactly(1L,2L);
        assertThat(beats.getValue().runningCount()).isEqualTo(1);assertThat(beats.getValue().pendingCount()).isEqualTo(2);
        assertThat(beats.getValue().sessionId()).isEqualTo(registered.getValue().sessionId());
    }
    @Test void uncertainRegistrationReusesSessionAndUnavailableSchedulerDoesNotStopFutureHeartbeats() {
        doThrow(new NodeClientException(503,"injected timeout")).doAnswer(call->{ Registration r=call.getArgument(0);return new Acknowledgement(r.workerId(),r.sessionId(),1,0,Instant.now().plusSeconds(30)); }).when(client).register(any());
        registration.pulse();registration.pulse();
        var registered=ArgumentCaptor.forClass(Registration.class);verify(client,times(2)).register(registered.capture());
        assertThat(registered.getAllValues().getFirst()).isEqualTo(registered.getAllValues().getLast());
        verify(client).heartbeat(any());
    }
    @Test void missingRegistrationReRegistersSameSessionInsteadOfInventingNewIdentity() {
        doThrow(new NodeClientException(404,"NODE_NOT_REGISTERED")).doAnswer(call->{ Heartbeat h=call.getArgument(0);return new Acknowledgement(h.workerId(),h.sessionId(),1,h.sequence(),Instant.now().plusSeconds(30)); }).when(client).heartbeat(any());
        registration.pulse();registration.pulse();
        var registered=ArgumentCaptor.forClass(Registration.class);verify(client,times(2)).register(registered.capture());
        assertThat(registered.getAllValues().getFirst().sessionId()).isEqualTo(registered.getValue().sessionId());
    }
}
