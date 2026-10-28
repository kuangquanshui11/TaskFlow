package com.taskflow.scheduler.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.dto.DispatchEvent;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.flyway.target=7", "taskflow.dispatch.transport=rabbitmq"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class WorkerClusterMigrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired IRunService runs;
    @Autowired IDispatchRelayService relay;
    @Autowired DispatchOutboxMapper outbox;
    @MockitoBean IDispatchPublisher publisher;

    @Test void upgradePreservesLegacyMessagesAndDeadlinesWithoutGuessingTheirTarget() {
        var run = UUID.randomUUID(); var attempt = UUID.randomUUID();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); var deadline = now.plusSeconds(60);
        var snapshot = new Snapshot(7, 1, "legacy", "demoSuccess", json.readTree("{}"), 50, 1, 1, "MANUAL", null, "UTC", "SKIP", false, null);
        var message = json.writeValueAsString(new DispatchEvent(1, attempt, new Dispatch(attempt, run, 1, 1, deadline, snapshot)));
        jdbc.update("INSERT INTO run_execution (id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,trigger_type) VALUES (?,7,1,?,?,'READY',?,1,?,?,'MANUAL')",
                run.toString(), UUID.randomUUID().toString(), "a".repeat(64), json.writeValueAsString(snapshot), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO run_attempt (id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at,expires_at,started_at) VALUES (?,?,1,1,'DISPATCHING',1,?,?,?)",
                attempt.toString(), run.toString(), Timestamp.from(now), Timestamp.from(deadline), Timestamp.from(now));
        jdbc.update("INSERT INTO dispatch_outbox (event_id,attempt_id,state,message_json,expires_at,publish_attempts,next_publish_at,claim_version,created_at) VALUES (?,?,'READY',?,?,0,?,0,?)",
                attempt.toString(), attempt.toString(), message, Timestamp.from(deadline), Timestamp.from(now), Timestamp.from(now));
        var migration = Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load();
        migration.migrate(); migration.migrate();
        runs.dispatchPending(); relay.relay();
        var event = outbox.find(attempt, false).orElseThrow();
        assertThat(event.messageJson()).isEqualTo(message); assertThat(event.expiresAt()).isEqualTo(deadline);
        assertThat(event.target()).isNull(); assertThat(event.publishAttempts()).isZero(); verifyNoInteractions(publisher);
        var stored = runs.selectById(run).attempts().getFirst();
        assertThat(stored.targetWorkerId()).isNull(); assertThat(stored.leaseVersion()).isEqualTo(1); assertThat(stored.dispatchCount()).isEqualTo(1);
    }
}
