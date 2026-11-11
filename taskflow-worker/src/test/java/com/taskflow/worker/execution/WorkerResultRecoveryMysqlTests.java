package com.taskflow.worker.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.worker.execution.client.SchedulerLeaseClient;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Dispatch;
import com.taskflow.worker.execution.dto.ExecutionProtocol.LeaseAcknowledgement;
import com.taskflow.worker.execution.dto.ExecutionProtocol.LeaseRequest;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.exception.ExecutionException;
import com.taskflow.worker.execution.mapper.ExecutionMapper;
import com.taskflow.worker.execution.service.IExecutionLeaseService;
import com.taskflow.worker.execution.service.IExecutionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles({"test", "test-result-mysql"})
@ContextConfiguration(initializers = WorkerResultRecoveryMysqlTests.IsolatedDatabase.class)
@EnabledIfEnvironmentVariable(named = "TASKFLOW_WORKER_RESULT_TEST_SCHEMA", matches = "taskflow_it_worker_result_[a-f0-9]{32}")
class WorkerResultRecoveryMysqlTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionMapper mapper;
    @Autowired JsonMapper json;
    @Autowired IExecutionService executions;
    @Autowired IExecutionLeaseService leases;
    @Autowired MeterRegistry metrics;
    @MockitoBean SchedulerLeaseClient leaseClient;

    @BeforeEach void configureLease() {
        when(leaseClient.updateLease(any())).thenAnswer(call -> {
            LeaseRequest request = call.getArgument(0);
            var command = json.readValue(mapper.find(request.attemptId()).orElseThrow().dispatchJson(), Dispatch.class);
            var deadline = command.expiresAt();
            return new LeaseAcknowledgement(request.attemptId(), request.runId(), request.leaseVersion(), request.workerId(),
                    request.sessionId(), Instant.now(), deadline, deadline, deadline);
        });
    }

    @Test void sqlOutageBeyondBudgetReleasesCapacityAfterWritesResume() {
        var first = command();
        var second = command();
        var third = command();
        var fourth = command();
        jdbc.execute("""
                CREATE TRIGGER worker_test_fail_result BEFORE UPDATE ON worker_execution FOR EACH ROW
                BEGIN
                    IF OLD.state = 'RUNNING' AND NEW.state IN ('SUCCEEDED', 'FAILED') THEN
                        SIGNAL SQLSTATE '40001' SET MESSAGE_TEXT = 'isolated result save failure';
                    END IF;
                END
                """);
        try {
            executions.insert(first);
            executions.insert(second);
            executions.executePending();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(metrics
                    .get("taskflow.worker.result.recovery.pending").gauge().value()).isEqualTo(2));
            assertThat(events(first)).containsExactly("HANDLER_STARTED");
            assertThat(events(second)).containsExactly("HANDLER_STARTED");
            executions.insert(third);
            executions.insert(fourth);
            executions.executePending();
            assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("ACCEPTED");
            assertThatThrownBy(() -> executions.insert(command())).isInstanceOfSatisfying(ExecutionException.class,
                    error -> assertThat(error.status()).isEqualTo(429));
            assertThatThrownBy(executions::recoverPendingResults).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER worker_test_fail_result");
        }

        executions.recoverPendingResults();
        executions.recoverPendingResults();
        assertThat(events(first)).containsExactly("HANDLER_STARTED", "HANDLER_FINISHED", "RESULT_RECOVERED");
        assertThat(events(second)).containsExactly("HANDLER_STARTED", "HANDLER_FINISHED", "RESULT_RECOVERED");
        assertThat(mapper.pendingCount()).isEqualTo(2);
        executions.executePending();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(mapper.pendingCount()).isZero());
        assertThat(mapper.find(third.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(mapper.find(fourth.attemptId()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }

    @Test void unknownResultAndTimelineRollBackTogether() {
        var orphan = command();
        executions.insert(orphan);
        assertThat(mapper.claim(orphan.attemptId(), leases.owner())).isTrue();
        jdbc.execute("""
                CREATE TRIGGER worker_test_fail_recovery_event BEFORE INSERT ON worker_attempt_event FOR EACH ROW
                BEGIN
                    IF NEW.event_type = 'RESULT_RECOVERY_UNKNOWN' THEN
                        SIGNAL SQLSTATE '40001' SET MESSAGE_TEXT = 'isolated recovery event failure';
                    END IF;
                END
                """);
        try {
            assertThatThrownBy(executions::recoverPendingResults).isInstanceOf(DataAccessException.class);
            assertThat(mapper.find(orphan.attemptId()).orElseThrow().state()).isEqualTo("RUNNING");
            assertThat(events(orphan)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER worker_test_fail_recovery_event");
        }
        executions.recoverPendingResults();
        var row = mapper.find(orphan.attemptId()).orElseThrow();
        assertThat(row.resultCode()).isEqualTo("RESULT_OUTCOME_UNKNOWN");
        assertThat(row.retryable()).isFalse();
        assertThat(events(orphan)).containsExactly("RESULT_RECOVERY_UNKNOWN");
        assertThat(mapper.pendingCount()).isZero();
    }

    private Dispatch command() {
        return new Dispatch(UUID.randomUUID(), UUID.randomUUID(), 1, 1, Instant.now().plusSeconds(60),
                new Snapshot(1L, 1L, "MySQL recovery", "demoSuccess", json.createObjectNode(), 30, 1, 1,
                        "MANUAL", null, "UTC", "SKIP", false, null));
    }

    private java.util.List<String> events(Dispatch command) {
        return jdbc.queryForList("SELECT event_type FROM worker_attempt_event WHERE attempt_id=? ORDER BY id",
                String.class, command.attemptId().toString());
    }

    static class IsolatedDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            var schema = env.getRequiredProperty("TASKFLOW_WORKER_RESULT_TEST_SCHEMA");
            var port = env.getRequiredProperty("TASKFLOW_WORKER_RESULT_TEST_PORT");
            var url = env.getProperty("spring.datasource.url", "");
            if (!schema.matches("taskflow_it_worker_result_[a-f0-9]{32}") || !port.matches("[0-9]{4,5}")
                    || !url.startsWith("jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?")) {
                throw new IllegalStateException("Worker result recovery tests require an isolated loopback MySQL schema");
            }
        }
    }
}
