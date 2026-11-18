CREATE TABLE history_archive_gate (id INT PRIMARY KEY);
INSERT INTO history_archive_gate (id) VALUES (1);

CREATE TABLE operation_audit_archive (
    id VARCHAR(36) PRIMARY KEY,
    actor VARCHAR(255) NOT NULL,
    action VARCHAR(24) NOT NULL,
    job_id BIGINT,
    config_version BIGINT,
    request_key VARCHAR(36),
    run_id VARCHAR(36),
    status VARCHAR(16) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    trace_id VARCHAR(32),
    occurred_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    check_count INT NOT NULL
);
CREATE INDEX idx_archive_audit_time ON operation_audit_archive(occurred_at, id);
CREATE INDEX idx_archive_audit_key ON operation_audit_archive(request_key);
CREATE INDEX idx_archive_audit_run ON operation_audit_archive(run_id, occurred_at, id);
CREATE INDEX idx_audit_archive_candidates ON operation_audit(updated_at, id);

CREATE TABLE reconciliation_check_archive (
    check_id VARCHAR(36) PRIMARY KEY,
    record_json LONGTEXT NOT NULL
);
CREATE TABLE job_sync_payload_archive (
    event_id VARCHAR(36) PRIMARY KEY,
    snapshot_json LONGTEXT NOT NULL
);
CREATE INDEX idx_sync_archive_candidates ON job_sync_outbox(updated_at, event_id);
CREATE INDEX idx_archive_scan_finished ON config_reconciliation_scan(finished_at, request_id);

CREATE VIEW operation_audit_history AS
SELECT id,actor,action,job_id,config_version,request_key,run_id,status,result_code,
       request_id,trace_id,occurred_at,updated_at,check_count
FROM operation_audit o WHERE NOT EXISTS (SELECT 1 FROM operation_audit_archive h WHERE h.id=o.id)
UNION ALL
SELECT id,actor,action,job_id,config_version,request_key,run_id,status,result_code,
       request_id,trace_id,occurred_at,updated_at,check_count
FROM operation_audit_archive;
