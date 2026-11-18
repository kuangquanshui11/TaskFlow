CREATE TABLE history_archive_gate (id INT PRIMARY KEY);
INSERT INTO history_archive_gate (id) VALUES (1);

CREATE TABLE run_snapshot_archive (
    run_id VARCHAR(36) PRIMARY KEY,
    snapshot_json LONGTEXT NOT NULL,
    archived_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE run_attempt_detail_archive (
    attempt_id VARCHAR(36) PRIMARY KEY,
    message VARCHAR(1024) NOT NULL
);
CREATE TABLE run_cancellation_detail_archive (
    run_id VARCHAR(36) PRIMARY KEY,
    reason VARCHAR(250) NOT NULL
);
CREATE TABLE run_outcome_review_detail_archive (
    review_id BIGINT PRIMARY KEY,
    evidence VARCHAR(500) NOT NULL
);
CREATE TABLE dispatch_outbox_payload_archive (
    event_id VARCHAR(36) PRIMARY KEY,
    message_json LONGTEXT NOT NULL
);
CREATE TABLE scheduler_attempt_event_archive (
    id BIGINT PRIMARY KEY,
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
CREATE INDEX idx_archive_scheduler_event ON scheduler_attempt_event_archive(run_id, attempt_id, id);
CREATE INDEX idx_run_archive_candidates ON run_execution(finished_at, id);
CREATE INDEX idx_archive_dead_letter_run ON dispatch_dead_letter(run_id, record_id);
