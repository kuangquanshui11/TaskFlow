package com.taskflow.worker.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.taskflow.worker.execution.config.ResultPersistenceProperties;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.model.ExecutionCompletion;
import com.taskflow.worker.execution.service.impl.ExecutionResultServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResultPersistenceTests {
    @Test void shutdownInterruptsSavingWithoutChangingTheBusinessOutcomeOrRetryingAgain() throws Exception {
        var mapper = mock(ExecutionMapper.class);
        var firstSave = new CountDownLatch(1);
        when(mapper.finish(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any())).thenAnswer(call -> {
            firstSave.countDown();
            throw new DataAccessResourceFailureException("database unavailable");
        });
        var metrics = new SimpleMeterRegistry();
        try {
            var service = new ExecutionResultServiceImpl(mapper, new ResultPersistenceProperties(3, Duration.ofSeconds(5)),
                    metrics, mock(PlatformTransactionManager.class));
            var completion = completion();
            var stopped = new AtomicBoolean();
            var interruptPreserved = new AtomicBoolean();
            var worker = Thread.ofPlatform().start(() -> {
                stopped.set(!service.save(completion));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            });
            try {
                assertThat(firstSave.await(2, TimeUnit.SECONDS)).isTrue();
                worker.interrupt(); worker.join(3000);
                assertThat(worker.isAlive()).isFalse();
                assertThat(stopped).isTrue();
                assertThat(interruptPreserved).isTrue();
                verify(mapper, times(1)).finish(completion.attemptId(), "SUCCEEDED", "SUCCEEDED", "done", false, true, completion.finishedAt());
                assertThat(metrics.get("taskflow.worker.result.persistence").tag("outcome", "interrupted").counter().count()).isEqualTo(1);
                assertThat(metrics.get("taskflow.worker.result.persistence.active").gauge().value()).isZero();
            } finally { worker.interrupt(); worker.join(3000); }
        } finally { metrics.close(); }
    }

    @Test void singleAttemptPolicyStopsImmediatelyAfterDatabaseFailure() {
        var mapper = mock(ExecutionMapper.class);
        when(mapper.finish(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var metrics = new SimpleMeterRegistry();
        try {
            var service = new ExecutionResultServiceImpl(mapper, new ResultPersistenceProperties(1, Duration.ofSeconds(5)),
                    metrics, mock(PlatformTransactionManager.class));
            assertThat(service.save(completion())).isFalse();
            verify(mapper, times(1)).finish(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
            verifyNoMoreInteractions(mapper);
            assertThat(metrics.get("taskflow.worker.result.persistence").tag("outcome", "exhausted").counter().count()).isEqualTo(1);
        } finally { metrics.close(); }
    }

    @Test void failedTransactionStartUsesTheSameResultAndRetryBudget() {
        var mapper = mock(ExecutionMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("database unavailable"))
                .thenReturn(new SimpleTransactionStatus());
        var completion = completion();
        when(mapper.finish(completion.attemptId(), "SUCCEEDED", "SUCCEEDED", "done", false, true, completion.finishedAt()))
                .thenReturn(true);
        var metrics = new SimpleMeterRegistry();
        try {
            var service = new ExecutionResultServiceImpl(mapper, new ResultPersistenceProperties(2, Duration.ofMillis(1)), metrics, manager);
            assertThat(service.save(completion)).isTrue();
            verify(manager, times(2)).getTransaction(any());
            verify(mapper, times(1)).finish(completion.attemptId(), "SUCCEEDED", "SUCCEEDED", "done", false, true, completion.finishedAt());
        } finally { metrics.close(); }
    }

    @Test void invalidRetryPoliciesAreRejectedBeforeStartingAWorker() {
        for (int count : new int[] { 0, -1, 11 }) {
            assertThatThrownBy(() -> new ResultPersistenceProperties(count, Duration.ofMillis(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (var delay : new Duration[] { null, Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1), Duration.ofSeconds(5).plusNanos(1) }) {
            assertThatThrownBy(() -> new ResultPersistenceProperties(3, delay)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new ResultPersistenceProperties(1, Duration.ofMillis(1))).doesNotThrowAnyException();
        assertThatCode(() -> new ResultPersistenceProperties(10, Duration.ofSeconds(5))).doesNotThrowAnyException();
    }

    private ExecutionCompletion completion() {
        return new ExecutionCompletion(UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", "SUCCEEDED", "done", false, true, Instant.now());
    }
}
