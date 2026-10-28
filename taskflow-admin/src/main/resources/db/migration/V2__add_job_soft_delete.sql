ALTER TABLE job_definition
    ADD COLUMN deleted_at DATETIME(3) DEFAULT NULL COMMENT '软删除时间，UTC；NULL 表示未删除',
    ADD KEY idx_job_deleted_id (deleted_at, id),
    ADD KEY idx_job_deleted_enabled_id (deleted_at, enabled, id),
    DROP INDEX idx_job_enabled_id;
