CREATE TABLE scheduler_attempt_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    worker_id VARCHAR(100),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    lease_version BIGINT NOT NULL,
    sequence_number INT,
    state VARCHAR(20),
    result_code VARCHAR(64),
    deadline_at TIMESTAMP(3)
);
CREATE INDEX idx_scheduler_attempt_event ON scheduler_attempt_event(run_id, attempt_id, id);
