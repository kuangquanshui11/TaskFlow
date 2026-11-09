ALTER TABLE run_execution ADD COLUMN source_run_id VARCHAR(36);
ALTER TABLE run_execution ADD COLUMN rerun_actor VARCHAR(255);
ALTER TABLE run_execution ADD CONSTRAINT ck_run_rerun_origin CHECK (
    (source_run_id IS NULL AND rerun_actor IS NULL)
    OR (source_run_id IS NOT NULL AND rerun_actor IS NOT NULL AND trigger_type = 'MANUAL')
);
