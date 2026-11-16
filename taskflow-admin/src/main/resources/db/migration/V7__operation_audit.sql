CREATE TABLE operation_audit (
    id VARCHAR(36) PRIMARY KEY,
    actor VARCHAR(255) NOT NULL,
    action VARCHAR(24) NOT NULL,
    job_id BIGINT,
    config_version BIGINT,
    request_key VARCHAR(36) UNIQUE,
    run_id VARCHAR(36),
    status VARCHAR(16) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    trace_id VARCHAR(32),
    occurred_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    check_count INT NOT NULL DEFAULT 0,
    next_check_at TIMESTAMP(3)
);
CREATE INDEX idx_audit_time ON operation_audit(occurred_at, id);
CREATE INDEX idx_audit_job ON operation_audit(job_id, occurred_at, id);
CREATE INDEX idx_audit_due ON operation_audit(status, next_check_at, id);
CREATE INDEX idx_audit_run ON operation_audit(run_id, occurred_at, id);
