package com.taskflow.admin.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.*;

class ReconciliationMigrationTests {
    @Test void productionV5AddsAutomaticRoundsAndKeepsManualControlRows() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript(new ClassPathResource("db/migration/V4__config_reconciliation.sql").getPath())
                .addScript("db/migration/V5__automatic_config_reconciliation.sql").build();
        try {
            var jdbc = new JdbcTemplate(database);
            assertThat(jdbc.queryForList("SELECT gate_name FROM config_reconciliation_gate ORDER BY gate_name", String.class))
                    .containsExactly("automatic", "repair", "scan");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_reconciliation_round", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_reconciliation_scan", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_reconciliation_check", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM config_reconciliation_repair", Long.class)).isZero();
        } finally { database.shutdown(); }
    }
}
