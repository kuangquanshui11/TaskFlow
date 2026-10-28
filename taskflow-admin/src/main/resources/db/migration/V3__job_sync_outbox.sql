CREATE TABLE job_sync_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(3),
    last_error_code VARCHAR(64),
    acknowledged_version BIGINT,
    synced_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_job_sync_version UNIQUE (job_id, config_version)
);
CREATE INDEX idx_job_sync_pending ON job_sync_outbox (state, next_attempt_at);
CREATE INDEX idx_job_sync_lease ON job_sync_outbox (state, lease_until);

-- 升级前已有配置也必须同步，包括删除墓碑；快照在迁移时固定，不在发送时读取可变 Job。
INSERT INTO job_sync_outbox (event_id, job_id, config_version, deleted, snapshot_json)
SELECT UUID(), id, config_version, deleted_at IS NOT NULL,
       JSON_OBJECT('name', name, 'description', description, 'handlerKey', handler_key,
           'payloadJson', CAST(payload_json AS CHAR), 'scheduleType', schedule_type,
           'cronExpression', cron_expression, 'timeZone', time_zone,
           'enabled', JSON_EXTRACT(IF(enabled = 1, 'true', 'false'), '$'),
           'timeoutSeconds', timeout_seconds, 'maxAttempts', max_attempts,
           'retryIntervalSeconds', retry_interval_seconds, 'misfirePolicy', misfire_policy)
FROM job_definition;
