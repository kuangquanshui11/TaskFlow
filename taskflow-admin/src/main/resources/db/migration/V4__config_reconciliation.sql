CREATE TABLE config_reconciliation_gate (
    gate_name VARCHAR(16) PRIMARY KEY,
    next_allowed_at TIMESTAMP(3) NOT NULL
);
INSERT INTO config_reconciliation_gate (gate_name,next_allowed_at) VALUES ('scan','2000-01-01 00:00:00'),('repair','2000-01-01 00:00:00');

CREATE TABLE config_reconciliation_scan (
    request_id VARCHAR(36) PRIMARY KEY,
    after_job_id BIGINT NOT NULL,
    batch_size INT NOT NULL,
    next_after_job_id BIGINT NOT NULL,
    has_more BOOLEAN NOT NULL,
    state VARCHAR(16) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3)
);
CREATE TABLE config_reconciliation_check (
    check_id VARCHAR(36) PRIMARY KEY,
    scan_id VARCHAR(36) NOT NULL,
    job_id BIGINT NOT NULL,
    record_json LONGTEXT NOT NULL,
    repair_request_id VARCHAR(36),
    CONSTRAINT uk_config_reconciliation_job UNIQUE (scan_id,job_id)
);
CREATE TABLE config_reconciliation_repair (
    request_id VARCHAR(36) PRIMARY KEY,
    check_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36),
    outcome VARCHAR(32) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX idx_config_reconciliation_repair_check ON config_reconciliation_repair (check_id);
