ALTER TABLE run_execution ADD COLUMN started_at TIMESTAMP(3) NULL;
ALTER TABLE run_execution ADD COLUMN finished_at TIMESTAMP(3) NULL;

UPDATE run_execution SET
    started_at = (SELECT MIN(a.started_at) FROM run_attempt a WHERE a.run_id = run_execution.id),
    finished_at = (SELECT MAX(a.finished_at) FROM run_attempt a WHERE a.run_id = run_execution.id)
WHERE state IN ('SUCCEEDED', 'FAILED');

CREATE INDEX idx_run_created_state ON run_execution(created_at, state);
