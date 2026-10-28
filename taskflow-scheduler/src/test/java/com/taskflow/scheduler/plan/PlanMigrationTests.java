package com.taskflow.scheduler.plan;

import java.time.Clock;
import java.time.Instant;
import com.taskflow.scheduler.plan.dto.PlanSyncRequest.Configuration;
import com.taskflow.scheduler.plan.service.IPlanService;
import com.taskflow.scheduler.plan.model.PlanRecords.CalculationState;
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

@SpringBootTest(properties = "spring.flyway.target=2")
@ActiveProfiles(resolver = PlanMigrationTests.DatabaseProfiles.class)
@ContextConfiguration(initializers = PlanMigrationTests.Guard.class)
class PlanMigrationTests {
    static final String MYSQL_URL = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/taskflow_it_plan_migration_[A-Za-z0-9_]+(?:\\?.*)?";
    static class DatabaseProfiles implements ActiveProfilesResolver {
        @Override public String[] resolve(Class<?> testClass) {
            var url = System.getenv("TASKFLOW_SCHEDULER_TEST_DB_URL");
            if (url == null || url.isBlank()) return new String[]{"test"};
            if (!url.matches(MYSQL_URL)) throw new IllegalArgumentException("仅允许本机专用计划迁移测试 schema");
            return new String[]{"test", "test-mysql"};
        }
    }
    public static class Guard implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            var url = context.getEnvironment().getProperty("spring.datasource.url", "");
            if (!url.startsWith("jdbc:h2:mem:taskflow-scheduler-") && !url.matches(MYSQL_URL))
                throw new IllegalStateException("计划迁移测试只能访问隔离数据库");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired IPlanService plans;
    @MockitoBean(name="planClock") Clock clock;

    @Test void v2ReplicasMigrateToPendingAndAreCalculatedOnceWithoutChangingVersionsOrCreatingRuns() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T00:00:00Z"));
        String oldRunId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO run_execution (id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at)
                VALUES (?,?,?,?,?,'SUCCEEDED','{}',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                """, oldRunId, 1, 7, java.util.UUID.randomUUID().toString(), "a".repeat(64));
        seed(1, true, false, "CRON", "0 0 9 * * *");
        seed(2, false, false, "CRON", "0 0 9 * * *");
        seed(3, true, false, "MANUAL", null);
        seed(4, false, true, "CRON", "0 0 9 * * *");
        seed(5, true, false, "CRON", "0 0 0 31 2 *");
        var migration = Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load();
        migration.migrate();
        assertThat(plans.selectByJobId(1).calculationState()).isEqualTo(CalculationState.PENDING);
        plans.initializePending();
        assertThat(plans.selectByJobId(1).nextFireTime()).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        assertThat(plans.selectByJobId(2).calculationState()).isEqualTo(CalculationState.DISABLED);
        assertThat(plans.selectByJobId(3).calculationState()).isEqualTo(CalculationState.MANUAL);
        assertThat(plans.selectByJobId(4).calculationState()).isEqualTo(CalculationState.DELETED);
        assertThat(plans.selectByJobId(5).calculationState()).isEqualTo(CalculationState.NO_FUTURE_FIRE);
        assertThat(plans.selectByJobId(1).configVersion()).isEqualTo(7);
        var before = plans.selectByJobId(1);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-27T00:00:00Z"));
        migration.migrate(); plans.initializePending();
        assertThat(plans.selectByJobId(1)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_execution",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM run_execution WHERE id=?",String.class,oldRunId)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT scheduled_at FROM run_execution WHERE id=?",java.sql.Timestamp.class,oldRunId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_sync_inbox",Long.class)).isZero();
    }

    private void seed(long id, boolean enabled, boolean deleted, String type, String cron) {
        var config = new Configuration("legacy",null,"demoSuccess",null,type,cron,"Asia/Shanghai",enabled,30,3,5,"SKIP");
        jdbc.update("INSERT INTO schedule_plan (job_id,config_version,deleted,enabled,snapshot_json,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(3))",
                id, 7, deleted, enabled, json.writeValueAsString(config));
    }
}
