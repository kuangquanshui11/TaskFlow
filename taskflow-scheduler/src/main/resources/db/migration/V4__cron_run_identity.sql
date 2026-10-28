ALTER TABLE run_execution ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE run_execution ADD COLUMN scheduled_at DATETIME(3);
-- MySQL/H2 均允许唯一键中存在多个 NULL，人工执行不会被定时计划的唯一键合并。
ALTER TABLE run_execution ADD CONSTRAINT uq_run_schedule UNIQUE (job_id, scheduled_at);
ALTER TABLE run_execution ADD CONSTRAINT ck_run_trigger CHECK (
    (trigger_type = 'MANUAL' AND scheduled_at IS NULL)
    OR (trigger_type = 'CRON' AND scheduled_at IS NOT NULL)
);
