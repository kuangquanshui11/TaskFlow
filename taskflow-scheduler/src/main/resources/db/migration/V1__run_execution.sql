CREATE TABLE run_execution (
    id VARCHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    request_key VARCHAR(36) NOT NULL UNIQUE,
    fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    current_attempt INT NOT NULL,
    result_code VARCHAR(64),
    message VARCHAR(1024),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX idx_run_job_created ON run_execution(job_id, created_at);
CREATE TABLE run_attempt (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    lease_version BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    dispatch_count INT NOT NULL,
    next_dispatch_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3),
    started_at TIMESTAMP(3),
    finished_at TIMESTAMP(3),
    result_code VARCHAR(64),
    message VARCHAR(1024),
    CONSTRAINT uq_run_attempt UNIQUE(run_id, attempt_number)
);
CREATE INDEX idx_attempt_dispatch ON run_attempt(state, next_dispatch_at);
CREATE INDEX idx_attempt_expiry ON run_attempt(state, expires_at);
