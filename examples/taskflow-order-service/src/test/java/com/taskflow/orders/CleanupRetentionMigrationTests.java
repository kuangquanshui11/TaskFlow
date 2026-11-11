package com.taskflow.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Batch;
import com.taskflow.orders.cleanup.dto.CleanupProtocol.Request;
import com.taskflow.orders.cleanup.service.ICleanupService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.flyway.target=7", "order.cache-enabled=false", "order.operations.enabled=false"})
@ActiveProfiles({"test", "test-cleanup"})
class CleanupRetentionMigrationTests {
    @Autowired JdbcTemplate db;
    @Autowired ICleanupService cleanup;

    @Test
    void versionEightPreservesLegacyBatchReplayAndOutboxRows() {
        String url = db.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(url).matches("jdbc:h2:mem:orders-[a-z0-9-]+(?:;.*)?"
                + "|jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/taskflow_it_order_[a-zA-Z0-9]+(?:\\?.*)?");
        var beforeUpgrade = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var cutoff = beforeUpgrade.minus(400, ChronoUnit.DAYS);
        db.update("""
                INSERT INTO expired_data_cleanup_run(request_key, cutoff, retention_days, batch_size)
                VALUES ('v7-cleanup', ?, 90, 1)
                """, Timestamp.from(cutoff));
        db.update("""
                INSERT INTO expired_data_cleanup_batch(request_key, batch_number, deleted_count, has_more)
                VALUES ('v7-cleanup', 1, 1, FALSE)
                """);
        db.update("""
                INSERT INTO stock_outbox(id, sku_id, created_at, next_attempt_at, attempts)
                VALUES ('v7-outbox', 1, ?, ?, 10)
                """, Timestamp.from(cutoff), Timestamp.from(cutoff));

        var flyway = Flyway.configure().dataSource(db.getDataSource()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(cleanup.delete(new Request("v7-cleanup", 90, 1, 1))).isEqualTo(new Batch(cutoff, 1, 1, false));
        assertThat(db.queryForObject("SELECT last_activity_at FROM expired_data_cleanup_run WHERE request_key = 'v7-cleanup'",
                Timestamp.class).toInstant()).isAfter(cutoff.plus(90, ChronoUnit.DAYS));
        assertThat(db.queryForObject("SELECT attempts FROM stock_outbox WHERE id = 'v7-outbox'", Integer.class)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT generation FROM stock_outbox WHERE id = 'v7-outbox'", Long.class)).isZero();

        db.update("DELETE FROM expired_data_cleanup_batch WHERE request_key = 'v7-cleanup'");
        db.update("DELETE FROM expired_data_cleanup_run WHERE request_key = 'v7-cleanup'");
        db.update("DELETE FROM stock_outbox WHERE id = 'v7-outbox'");
    }
}
