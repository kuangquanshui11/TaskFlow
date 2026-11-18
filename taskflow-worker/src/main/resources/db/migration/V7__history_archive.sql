CREATE TABLE history_archive_gate (id INT PRIMARY KEY);
INSERT INTO history_archive_gate (id) VALUES (1);

CREATE TABLE worker_execution_detail_archive (
    attempt_id VARCHAR(36) PRIMARY KEY,
    dispatch_json LONGTEXT NOT NULL,
    message VARCHAR(1024),
    archived_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE worker_attempt_event_archive (
    id BIGINT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    worker_id VARCHAR(100),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    report_number INT,
    state VARCHAR(20),
    result_code VARCHAR(64)
);
CREATE INDEX idx_archive_worker_event ON worker_attempt_event_archive(run_id, attempt_id, worker_id, id);
CREATE INDEX idx_worker_archive_candidates ON worker_execution(result_acknowledged_at, attempt_id);
CREATE INDEX idx_worker_event_attempt ON worker_attempt_event(attempt_id, id);
