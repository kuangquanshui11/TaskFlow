package com.taskflow.scheduler.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.flyway.target=4")
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class DispatchMigrationTests {
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Test void v4PendingAttemptsBackfillIntentWithoutResettingLeaseOrReplayingTerminalWork() {
        var ready=seed(); var dispatching=seed(); var finished=seed();
        var oldLease=Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE run_attempt SET state='DISPATCHING',dispatch_count=1,expires_at=? WHERE id=?",
                Timestamp.from(oldLease),dispatching.attemptId().toString());
        jdbc.update("UPDATE run_attempt SET state='SUCCEEDED' WHERE id=?",finished.attemptId().toString());
        jdbc.update("UPDATE run_execution SET state='SUCCEEDED' WHERE id=?",finished.id().toString());
        var migration=Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load();
        migration.migrate(); migration.migrate();
        assertThat(jdbc.queryForList("SELECT attempt_id FROM dispatch_outbox",String.class))
                .containsExactlyInAnyOrder(ready.attemptId().toString(),dispatching.attemptId().toString());
        assertThat(jdbc.queryForList("SELECT state FROM dispatch_outbox",String.class)).containsOnly("WAITING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_outbox WHERE message_json IS NOT NULL",Long.class)).isZero();
        assertThat(runs.selectById(dispatching.id()).attempts().getFirst().expiresAt()).isEqualTo(oldLease);
        assertThat(runs.selectById(finished.id()).state()).isEqualTo("SUCCEEDED");
    }
    private record Legacy(UUID id, UUID attemptId) { }
    private Legacy seed() {
        var id = UUID.randomUUID(); var attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO run_execution (id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,trigger_type) VALUES (?,7,2,?,?,'READY',?,1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'MANUAL')",
                id.toString(), UUID.randomUUID().toString(), "a".repeat(64), json.writeValueAsString(request().snapshot()));
        jdbc.update("INSERT INTO run_attempt (id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at) VALUES (?,?,1,1,'READY',0,CURRENT_TIMESTAMP(3))", attempt.toString(), id.toString());
        return new Legacy(id, attempt);
    }
    private Create request() {
        return new Create(UUID.randomUUID(),new Snapshot(7,2,"migration","demoSuccess",json.readTree("{}"),
                30,1,5,"MANUAL",null,"UTC","SKIP",false,null));
    }
}
