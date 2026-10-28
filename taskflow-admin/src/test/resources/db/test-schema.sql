-- H2 runs HTTP contracts only. MySQL migration/JSON/CHECK behavior is tested separately.
CREATE TABLE job_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    handler_key VARCHAR(100) NOT NULL,
    payload_json CLOB,
    schedule_type VARCHAR(16) DEFAULT 'MANUAL' NOT NULL,
    cron_expression VARCHAR(100),
    time_zone VARCHAR(64) DEFAULT 'Asia/Shanghai' NOT NULL,
    enabled TINYINT DEFAULT 0 NOT NULL,
    timeout_seconds INT DEFAULT 30 NOT NULL,
    max_attempts INT DEFAULT 3 NOT NULL,
    retry_interval_seconds INT DEFAULT 5 NOT NULL,
    misfire_policy VARCHAR(16) DEFAULT 'SKIP' NOT NULL,
    config_version BIGINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    deleted_at TIMESTAMP(3)
);

CREATE TABLE job_sync_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    snapshot_json CLOB NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(3),
    last_error_code VARCHAR(64),
    acknowledged_version BIGINT,
    synced_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_job_sync_version UNIQUE (job_id, config_version)
);
CREATE INDEX idx_job_sync_pending ON job_sync_outbox (state, next_attempt_at);
CREATE INDEX idx_job_sync_lease ON job_sync_outbox (state, lease_until);
