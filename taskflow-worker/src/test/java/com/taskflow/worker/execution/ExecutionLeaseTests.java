package com.taskflow.worker.execution;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
import com.taskflow.worker.execution.client.SchedulerReportClient;
import com.taskflow.worker.execution.dto.ExecutionProtocol.*;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"taskflow.execution.lease.renewal-interval=100ms",
        "taskflow.execution.lease.safety-margin=100ms", "taskflow.execution.lease.watchdog-interval=10ms"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.worker.support.IsolatedTestDatabaseInitializer.class)
class ExecutionLeaseTests {
    @Autowired IExecutionService executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean ExecutionMapper mapper;
    @MockitoSpyBean IDemoHandlerService handlers;
    @MockitoBean SchedulerLeaseClient client;
    @MockitoBean SchedulerReportClient reports;
    private final Map<UUID, Dispatch> commands = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> requests = new ConcurrentHashMap<>();

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM worker_execution");
        doAnswer(call -> acknowledgement(call.getArgument(0))).when(client).updateLease(any());
    }
    @Test void independentRenewalKeepsLongHandlerAliveBeyondInitialShortLease() throws Exception {
        var command = command("demoSlow", 3, 1200);
        executions.insert(command); executions.executePending(); finished(command);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(requests.get(command.attemptId()).get()).isGreaterThan(3);
        verify(handlers, times(1)).execute(any(), any());
        executions.insert(command); executions.executePending();
        verify(handlers, times(1)).execute(any(), any());
    }
    @Test void temporaryNetworkFailureWithinConfirmedWindowDoesNotImmediatelyCancel() {
        var failures = new AtomicInteger();
        doAnswer(call -> {
            if (failures.incrementAndGet() == 2) throw new IllegalStateException("temporary network failure");
            return acknowledgement(call.getArgument(0));
        }).when(client).updateLease(any());
        var command = command("demoSlow", 3, 700);
        executions.insert(command); executions.executePending(); finished(command);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(failures.get()).isGreaterThan(3);
    }
    @Test void explicitRejectionCancelsHandlerAndNeverRequestsBusinessRetry() {
        doAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            if (requests.containsKey(request.attemptId())) throw new ExecutionException(409, "LEASE_REJECTED", "rejected");
            return acknowledgement(request);
        }).when(client).updateLease(any());
        var command = command("demoSlow", 3, 2000);
        executions.insert(command); executions.executePending(); finished(command);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.resultCode()).isEqualTo("LEASE_LOST");
        assertThat(row.retryable()).isFalse();
    }
    @Test void blockedRenewalDoesNotBlockIndependentExpiryWatchdog() throws Exception {
        var blocked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            if (requests.containsKey(request.attemptId())) {
                blocked.countDown(); release.await(3, TimeUnit.SECONDS);
                return acknowledgement(request);
            }
            return acknowledgement(request);
        }).when(client).updateLease(any());
        var command = command("demoSlow", 3, 2000);
        try {
            executions.insert(command); executions.executePending();
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(mapper.find(command.attemptId()).orElseThrow().resultCode()).isEqualTo("LEASE_LOST"));
        } finally { release.countDown(); }
    }
    @Test void initialUnconfirmedLeaseNeverCallsHandler() throws Exception {
        doThrow(new IllegalStateException("lost response")).when(client).updateLease(any());
        var command = command("demoSuccess", 3, 0);
        executions.insert(command); executions.executePending(); finished(command);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.resultCode()).isEqualTo("LEASE_UNCONFIRMED");
        assertThat(row.retryable()).isFalse();
        verify(handlers, never()).execute(any(), any());
    }
    @Test void expiredExecutionBudgetNeverStartsHandlerEvenIfMessageHasGraceRemaining() throws Exception {
        var original = command("demoSuccess", 1, 0);
        var command = new Dispatch(original.attemptId(), original.runId(), 1, 1, Instant.now().plusSeconds(8), original.snapshot());
        commands.put(command.attemptId(), command);
        executions.insert(command); executions.executePending(); finished(command);
        assertThat(mapper.find(command.attemptId()).orElseThrow().resultCode()).isEqualTo("EXECUTION_TIMEOUT");
        verify(handlers, never()).execute(any(), any());
    }
    @Test void healthyRenewalCannotExtendExecutionTimeout() {
        var command = command("demoSlow", 1, 2000);
        executions.insert(command); executions.executePending(); finished(command);
        var row = mapper.find(command.attemptId()).orElseThrow();
        assertThat(row.resultCode()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(row.retryable()).isFalse();
        assertThat(requests.get(command.attemptId()).get()).isGreaterThan(3);
    }
    @Test void ignoringInterruptRetainsSlotsUntilHandlersActuallyExit() throws Exception {
        var release = new CountDownLatch(1);
        var contexts = new ConcurrentHashMap<UUID, ExecutionContext>();
        var first = command("demoSuccess", 3, 0); var second = command("demoSuccess", 3, 0);
        var third = command("demoSuccess", 3, 0);
        doAnswer(call -> {
            ExecutionContext context = call.getArgument(1);
            if (context.attemptId().equals(third.attemptId())) return call.callRealMethod();
            contexts.put(context.attemptId(), context);
            boolean done = false;
            while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { } }
            return "ignored interruption";
        }).when(handlers).execute(any(), any());
        doAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            if (!request.attemptId().equals(third.attemptId()) && requests.containsKey(request.attemptId()))
                throw new ExecutionException(409, "LEASE_REJECTED", "rejected");
            return acknowledgement(request);
        }).when(client).updateLease(any());
        try {
            executions.insert(first); executions.insert(second); executions.executePending();
            await().atMost(Duration.ofSeconds(2)).until(() -> contexts.size() == 2 && contexts.values().stream().allMatch(ExecutionContext::cancelled));
            executions.insert(third); executions.executePending();
            assertThat(mapper.find(first.attemptId()).orElseThrow().state()).isEqualTo("RUNNING");
            assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
            verify(handlers, times(2)).execute(any(), any());
        } finally { release.countDown(); }
        finished(first); finished(second);
        assertThat(mapper.find(first.attemptId()).orElseThrow().resultCode()).isEqualTo("LEASE_LOST");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            executions.executePending();
            assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        });
    }
    @Test void resultPersistenceKeepsRenewingWithoutInterruptingTheSave() throws Exception {
        var command = command("demoSuccess", 1, 0);
        var saving = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            saving.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(mapper).finish(eq(command.attemptId()), anyString(), anyString(), anyString(), anyBoolean(), any());
        try {
            executions.insert(command); executions.executePending();
            assertThat(saving.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> requests.get(command.attemptId()).get() > 10);
        } finally { release.countDown(); }
        finished(command);
        assertThat(mapper.find(command.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    private LeaseAcknowledgement acknowledgement(LeaseRequest request) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        requests.computeIfAbsent(request.attemptId(), id -> new AtomicInteger()).incrementAndGet();
        var command = commands.get(request.attemptId()); var now = Instant.now();
        var until = now.plusMillis(600);
        if (until.isAfter(command.expiresAt())) until = command.expiresAt();
        return new LeaseAcknowledgement(request.attemptId(), request.runId(), request.leaseVersion(), request.workerId(), request.sessionId(),
                now, until, command.expiresAt().minusSeconds(10), command.expiresAt());
    }
    private Dispatch command(String key, int timeout, int millis) {
        var command = new Dispatch(UUID.randomUUID(), UUID.randomUUID(), 1, 1, Instant.now().plusSeconds(timeout + 10),
                new Snapshot(7, 1, "lease-test", key, json.readTree("{\"durationMillis\":" + millis + "}"), timeout, 3, 1, "MANUAL", null, "UTC", "SKIP", false, null));
        commands.put(command.attemptId(), command); return command;
    }
    private void finished(Dispatch command) {
        await().atMost(Duration.ofSeconds(4)).until(() -> Set.of("SUCCEEDED", "FAILED").contains(mapper.find(command.attemptId()).orElseThrow().state()));
    }
}
