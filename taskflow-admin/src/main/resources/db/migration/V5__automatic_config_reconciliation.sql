ALTER TABLE config_reconciliation_check ADD COLUMN round_id VARCHAR(36);
CREATE INDEX idx_config_reconciliation_round_check ON config_reconciliation_check (round_id,job_id);
INSERT INTO config_reconciliation_gate (gate_name,next_allowed_at) VALUES ('automatic','2000-01-01 00:00:00');

CREATE TABLE config_reconciliation_round (
    round_id VARCHAR(36) PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    after_job_id BIGINT NOT NULL,
    admin_upper_job_id BIGINT NOT NULL,
    scheduler_upper_job_id BIGINT,
    scanned_count BIGINT NOT NULL,
    difference_count BIGINT NOT NULL,
    last_error_code VARCHAR(64),
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    lease_id VARCHAR(36)
);
CREATE INDEX idx_config_reconciliation_round_started ON config_reconciliation_round (started_at,round_id);
