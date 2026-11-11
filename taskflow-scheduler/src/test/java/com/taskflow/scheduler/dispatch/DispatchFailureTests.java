package com.taskflow.scheduler.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchFailures.*;
import com.taskflow.scheduler.dispatch.mapper.DispatchFailureMapper;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.*;
import com.taskflow.scheduler.execution.client.WorkerAttemptEventClient;
import com.taskflow.scheduler.execution.dto.AttemptEvents.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.exception.RunException;
import com.taskflow.scheduler.execution.service.IRunService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "taskflow.dispatch.transport=rabbitmq")
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class DispatchFailureTests {
    @Autowired IRunService runs;
    @Autowired IDispatchFailureService incidents;
    @Autowired IDispatchDeadLetterService collector;
    @Autowired DispatchFailureMapper failures;
    @Autowired DispatchOutboxMapper outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JsonMapper json;
    @MockitoBean IDispatchPublisher publisher;
    @MockitoBean WorkerAttemptEventClient workers;

    @Test void deadLetterDedupeAndAuditAreDurableWithoutChangingRunState() {
        var run = runs.insert(new Create(UUID.randomUUID(), new Snapshot(7, 1, "incident-test", "demoSuccess",
                json.createObjectNode(), 30, 1, 1, "MANUAL", null, "UTC", "SKIP", false, null)));
        var id = run.attempts().getFirst().id();
        runs.dispatchPending();
        var event = outbox.find(id, false).orElseThrow();
        assertThat(event.messageJson()).isNotNull();
        var message = message(event.messageJson(), id, event.target().queue());

        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            collector.record(message);
            throw new IllegalStateException("rollback before ACK");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(failures.recent(id, null, 5)).isEmpty();
        collector.record(message);
        collector.record(message);
        collector.record(message("{}", id, event.target().queue()));
        assertThat(failures.recent(id, null, 5)).hasSize(1);
        assertThat(failures.recent(null, id, 5)).hasSize(2);

        var record = incidents.select(run.id(), id, null).records().getFirst();
        assertThat(record.reason()).isEqualTo("rejected");
        assertThat(record.runId()).isEqualTo(run.id());
        assertThat(incidents.select(null, null, id).records()).hasSize(2);
        var acknowledge = request(record, Action.ACKNOWLEDGE);
        var acknowledged = incidents.update(acknowledge);
        assertThat(incidents.update(acknowledge)).isEqualTo(acknowledged);
        assertThatThrownBy(() -> incidents.update(new DispositionRequest(acknowledge.requestKey(), record.source(),
                record.id(), Action.ACKNOWLEDGE, "another-actor", "checked", "ledger-1")))
                .isInstanceOfSatisfying(RunException.class, failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        var resolve = request(record, Action.RESOLVE);
        assertThatThrownBy(() -> incidents.update(resolve)).isInstanceOfSatisfying(RunException.class,
                failure -> assertThat(failure.code()).isEqualTo("ATTEMPT_ACTIVE"));
        jdbc.update("UPDATE run_attempt SET expires_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)), id.toString());
        runs.dispatchPending();
        var authoritative = runs.selectById(run.id());
        assertThat(authoritative.attempts().getFirst().state()).isEqualTo("TIMED_OUT");
        incidents.update(resolve);
        assertThat(incidents.select(run.id(), id, null).records().getFirst().history())
                .extracting(Disposition::action).containsExactly(Action.ACKNOWLEDGE, Action.RESOLVE);
        assertThat(runs.selectById(run.id())).isEqualTo(authoritative);
        verifyNoInteractions(publisher);
    }

    @Test void combinesLocalExhaustionAndWorkerDiagnosisThenReportsWorkerUnavailable() {
        var run = runs.insert(new Create(UUID.randomUUID(), new Snapshot(8, 1, "report-test", "demoSuccess",
                json.createObjectNode(), 30, 1, 1, "MANUAL", null, "UTC", "SKIP", false, null)));
        var id = run.attempts().getFirst().id();
        runs.dispatchPending();
        jdbc.update("UPDATE dispatch_outbox SET state='FAILED',publish_attempts=3,last_error_code='MQ_CONFIRM_TIMEOUT' WHERE event_id=?", id.toString());
        var worker = outbox.find(id, false).orElseThrow().target().workerId();
        when(workers.snapshot(run.id(), id, worker, 100)).thenReturn(java.util.Optional.of(
                new WorkerEvents(run.id(), id, worker, List.of(), false, new ReportFailure("REPORT_AUTH_FAILED", 3, Instant.now()))));
        var report = incidents.select(run.id(), null, null);
        assertThat(report.workerStatus()).isEqualTo("AVAILABLE");
        assertThat(report.records()).extracting(Failure::reason).containsExactly("MQ_CONFIRM_TIMEOUT", "REPORT_AUTH_FAILED");
        var entry = report.records().getLast();
        incidents.update(request(entry, Action.ACKNOWLEDGE));
        when(workers.snapshot(run.id(), id, worker, 100)).thenReturn(java.util.Optional.empty());
        var offline = incidents.select(run.id(), id, null);
        assertThat(offline.workerStatus()).isEqualTo("UNAVAILABLE");
        assertThat(offline.records().getLast().history()).hasSize(1);
    }

    private static org.springframework.amqp.core.Message message(String body, UUID eventId, String source) {
        var message = MessageBuilder.withBody(body.getBytes(UTF_8)).setMessageId(eventId.toString())
                .setType("taskflow.dispatch.v1").setHeader("x-death", List.of(Map.of("queue", source, "reason", "rejected"))).build();
        message.getMessageProperties().setConsumerQueue("taskflow.worker.default.dead.v1");
        return message;
    }

    private static DispositionRequest request(Failure target, Action action) {
        return new DispositionRequest(UUID.randomUUID(), target.source(), target.id(), action, "operator", "checked", "ledger-1");
    }
}
