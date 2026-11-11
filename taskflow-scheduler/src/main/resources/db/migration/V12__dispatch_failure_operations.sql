CREATE TABLE dispatch_dead_letter (
    record_id CHAR(64) PRIMARY KEY,
    run_id VARCHAR(36),
    attempt_id VARCHAR(36),
    event_id VARCHAR(36),
    dead_queue VARCHAR(180) NOT NULL,
    source_queue VARCHAR(180),
    reason VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX idx_dispatch_dead_attempt ON dispatch_dead_letter(attempt_id, received_at, record_id);
CREATE INDEX idx_dispatch_dead_event ON dispatch_dead_letter(event_id, received_at, record_id);
CREATE INDEX idx_dispatch_dead_received ON dispatch_dead_letter(received_at, record_id);

CREATE TABLE dispatch_failure_disposition (
    request_key VARCHAR(36) PRIMARY KEY,
    source_kind VARCHAR(24) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    conclusion VARCHAR(250) NOT NULL,
    evidence VARCHAR(250) NOT NULL,
    recorded_at TIMESTAMP(3) NOT NULL,
    UNIQUE (source_kind, source_id, action)
);
