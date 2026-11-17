CREATE TABLE job_execution_gate (
    job_id BIGINT PRIMARY KEY
);

CREATE TABLE job_execution_slot (
    run_id VARCHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    serial_execution BOOLEAN NOT NULL,
    CONSTRAINT fk_execution_slot_run FOREIGN KEY (run_id) REFERENCES run_execution(id) ON DELETE CASCADE
);
CREATE INDEX idx_execution_slot_job ON job_execution_slot(job_id, serial_execution, run_id);

INSERT INTO job_execution_slot(run_id,job_id,serial_execution)
SELECT r.id,r.job_id,FALSE FROM run_execution r
WHERE r.state IN ('READY','RUNNING','RETRY_WAIT') AND EXISTS (
    SELECT 1 FROM run_attempt a WHERE a.run_id=r.id AND a.started_at IS NOT NULL
);
