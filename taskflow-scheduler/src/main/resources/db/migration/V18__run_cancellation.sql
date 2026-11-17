CREATE TABLE run_cancellation (
    run_id VARCHAR(36) PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(36) NOT NULL UNIQUE,
    reason VARCHAR(250) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    finished_at TIMESTAMP(3),
    worker_result_status VARCHAR(20),
    worker_result_code VARCHAR(64),
    worker_result_at TIMESTAMP(3),
    CONSTRAINT fk_cancellation_run FOREIGN KEY (run_id) REFERENCES run_execution(id) ON DELETE CASCADE
);
CREATE INDEX idx_cancellation_scan ON run_cancellation(outcome, deadline_at);
