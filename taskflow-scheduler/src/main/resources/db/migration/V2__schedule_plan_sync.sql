CREATE TABLE schedule_plan (
    job_id BIGINT PRIMARY KEY,
    config_version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE schedule_sync_inbox (
    event_id VARCHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_schedule_sync_version UNIQUE (job_id, config_version)
);
