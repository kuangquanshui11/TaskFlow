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
        var ready=runs.insert(request()); var dispatching=runs.insert(request()); var finished=runs.insert(request());
        var oldLease=Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE run_attempt SET state='DISPATCHING',dispatch_count=1,expires_at=? WHERE id=?",
                Timestamp.from(oldLease),dispatching.attempts().getFirst().id().toString());
        jdbc.update("UPDATE run_attempt SET state='SUCCEEDED' WHERE id=?",finished.attempts().getFirst().id().toString());
        jdbc.update("UPDATE run_execution SET state='SUCCEEDED' WHERE id=?",finished.id().toString());
        var migration=Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load();
        migration.migrate(); migration.migrate();
        assertThat(jdbc.queryForList("SELECT attempt_id FROM dispatch_outbox",String.class))
                .containsExactlyInAnyOrder(ready.attempts().getFirst().id().toString(),dispatching.attempts().getFirst().id().toString());
        assertThat(jdbc.queryForList("SELECT state FROM dispatch_outbox",String.class)).containsOnly("WAITING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_outbox WHERE message_json IS NOT NULL",Long.class)).isZero();
        assertThat(runs.selectById(dispatching.id()).attempts().getFirst().expiresAt()).isEqualTo(oldLease);
        assertThat(runs.selectById(finished.id()).state()).isEqualTo("SUCCEEDED");
    }
    private Create request() {
        return new Create(UUID.randomUUID(),new Snapshot(7,2,"migration","demoSuccess",json.readTree("{}"),
                30,1,5,"MANUAL",null,"UTC","SKIP",false,null));
    }
}
