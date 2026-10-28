CREATE TABLE worker_execution (
    attempt_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    dispatch_json LONGTEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    result_code VARCHAR(64),
    message VARCHAR(1024),
    retryable BOOLEAN NOT NULL,
    reported BOOLEAN NOT NULL,
    report_count INT NOT NULL,
    next_report_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3)
);
CREATE INDEX idx_worker_pending ON worker_execution(state, created_at);
CREATE INDEX idx_worker_report ON worker_execution(reported, next_report_at);
