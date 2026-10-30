package com.taskflow.admin.jobsync;

import java.sql.Timestamp;
import java.time.Instant;
import com.taskflow.admin.job.model.JobDefinition.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 专用空 MySQL schema：先迁移到 V2 并放入旧配置，再验证 V3 回填，不能复用已升到 V3 的 schema。 */
@SpringBootTest(properties = "spring.flyway.target=2")
@ActiveProfiles({"test", "test-mysql"})
@EnabledIfEnvironmentVariable(named = "TASKFLOW_TEST_DB_URL",
        matches = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/taskflow_it_sync_migration_[A-Za-z0-9_]+(?:\\?.*)?")
@ContextConfiguration(initializers = JobSyncMysqlMigrationTests.Guard.class)
@org.springframework.security.test.context.support.WithMockUser(authorities = {"job:write", "job:delete", "job:operate", "run:trigger", "job-sync:retry"})
@org.springframework.context.annotation.Import(com.taskflow.admin.support.TestSessionConfiguration.class)
class JobSyncMysqlMigrationTests {
    public static class Guard implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            if (!context.getEnvironment().getProperty("spring.datasource.url", "").matches(
                    "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/taskflow_it_sync_migration_[A-Za-z0-9_]+(?:\\?.*)?"))
                throw new IllegalStateException("配置同步迁移测试只能访问专用本机 MySQL schema");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @Test void v3BackfillsExistingConfigurationsAndDeletionTombstonesAsReadableImmutableSnapshots() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_definition", Long.class)).isZero();
        jdbc.update("""
                INSERT INTO job_definition (name,handler_key,payload_json,schedule_type,cron_expression,enabled,config_version)
                VALUES (?,?,?,?,?,?,?)
                """, "旧定时任务", "demoSuccess", "{\"nested\":{\"message\":\"中文\"}}", "CRON", "0 */5 * * * *", 1, 4);
        jdbc.update("""
                INSERT INTO job_definition (name,handler_key,enabled,config_version,deleted_at) VALUES (?,?,?,?,?)
                """, "旧删除任务", "demoSuccess", 0, 9, Timestamp.from(Instant.now()));
        Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForList("SELECT gate_name FROM config_reconciliation_gate ORDER BY gate_name", String.class))
                .containsExactly("repair", "scan");
        var records = jdbc.queryForList("SELECT * FROM job_sync_outbox ORDER BY config_version");
        assertThat(records).hasSize(2);
        var active = json.readValue((String) records.getFirst().get("snapshot_json"), Configuration.class);
        var deleted = json.readValue((String) records.getLast().get("snapshot_json"), Configuration.class);
        assertThat(active.enabled()).isTrue();
        assertThat(json.readTree(active.payloadJson()).get("nested").get("message").stringValue()).isEqualTo("中文");
        assertThat(deleted.enabled()).isFalse();
        assertThat(deleted.payloadJson()).isNull();
        assertThat(records.getFirst().get("state")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT deleted FROM job_sync_outbox WHERE config_version=9", Boolean.class)).isTrue();
        jdbc.update("UPDATE job_definition SET name='后续修改'");
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM job_sync_outbox WHERE config_version=4", String.class)).contains("旧定时任务");
    }
}
