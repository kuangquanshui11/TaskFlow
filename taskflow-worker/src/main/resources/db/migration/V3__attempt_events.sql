CREATE TABLE worker_attempt_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    worker_id VARCHAR(100),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    report_number INT,
    state VARCHAR(20),
    result_code VARCHAR(64)
);
CREATE INDEX idx_worker_attempt_event ON worker_attempt_event(run_id, attempt_id, id);
