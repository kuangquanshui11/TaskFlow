package com.taskflow.scheduler.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.flyway.target=8")
@ActiveProfiles("test")
@ContextConfiguration(initializers = com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class RunReportingMigrationTests {
    @Autowired JdbcTemplate jdbc;
    @Test void backfillsOnlyTerminalRowsAndCreatesIndex() throws Exception {
        var start = Instant.parse("2026-10-30T00:00:00Z");
        for (var state : new String[]{"SUCCEEDED", "FAILED", "READY", "RUNNING", "RETRY_WAIT"}) {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO run_execution(id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at) VALUES(?,1,1,?,'test',?,'{}',2,?,?)",
                    id, UUID.randomUUID().toString(), state, Timestamp.from(start), Timestamp.from(start));
            for (int n = 1; n <= 2; n++) jdbc.update("INSERT INTO run_attempt(id,run_id,attempt_number,lease_version,state,dispatch_count,next_dispatch_at,started_at,finished_at) VALUES(?,?,?,1,'FAILED',1,?,?,?)",
                    UUID.randomUUID().toString(), id, n, Timestamp.from(start), Timestamp.from(start.plusSeconds(n)), Timestamp.from(start.plusSeconds(n + 1)));
        }
        var flyway = Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load();
        flyway.migrate(); flyway.migrate();
        jdbc.query("SELECT state,started_at,finished_at FROM run_execution", rs -> {
            if (java.util.Set.of("SUCCEEDED", "FAILED").contains(rs.getString(1))) {
                assertThat(rs.getTimestamp(2).toInstant()).isEqualTo(start.plusSeconds(1));
                assertThat(rs.getTimestamp(3).toInstant()).isEqualTo(start.plusSeconds(3));
            } else { assertThat(rs.getTimestamp(2)).isNull(); assertThat(rs.getTimestamp(3)).isNull(); }
        });
        try (var connection = jdbc.getDataSource().getConnection(); var indexes = connection.getMetaData().getIndexInfo(null, null, "run_execution", false, false)) {
            var names = new java.util.ArrayList<String>();
            while (indexes.next()) names.add(indexes.getString("INDEX_NAME"));
            assertThat(names).contains("idx_run_created_state");
        }
    }
}
