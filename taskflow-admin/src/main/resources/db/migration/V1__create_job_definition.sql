-- Admin owns job configuration. Run/Attempt tables belong to Scheduler.
-- Requires MySQL 8.0.16+ for enforced CHECK constraints.
CREATE TABLE job_definition (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键',
    name VARCHAR(100) NOT NULL COMMENT '任务名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '任务说明',

    handler_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL
        COMMENT '处理器标识，例如 demoSuccess，区分大小写',
    payload_json JSON DEFAULT NULL COMMENT '任务参数，无参数时可为 NULL',

    schedule_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL：手动；CRON：定时',
    cron_expression VARCHAR(100) DEFAULT NULL
        COMMENT '六字段 Cron；仅 CRON 模式填写',
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai'
        COMMENT 'Cron 解析时区，例如 Asia/Shanghai',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用定时调度：0 停用，1 启用',

    timeout_seconds INT NOT NULL DEFAULT 30 COMMENT '单次执行超时时间，单位秒',
    max_attempts INT NOT NULL DEFAULT 3 COMMENT '最大执行尝试次数，包含首次执行',
    retry_interval_seconds INT NOT NULL DEFAULT 5 COMMENT '重试基础间隔，单位秒',
    misfire_policy VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'SKIP' COMMENT '错过计划：SKIP 跳过；FIRE_ONCE 补发一次',

    config_version BIGINT NOT NULL DEFAULT 1
        COMMENT '单个任务的配置版本，每次修改或启停时由应用加 1',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，连接使用 UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，连接使用 UTC',

    PRIMARY KEY (id),
    KEY idx_job_enabled_id (enabled, id),

    CONSTRAINT ck_job_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_job_handler CHECK (CHAR_LENGTH(TRIM(handler_key)) > 0),
    CONSTRAINT ck_job_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_job_schedule_type CHECK (schedule_type IN ('MANUAL', 'CRON')),
    CONSTRAINT ck_job_cron CHECK (
        (schedule_type = 'MANUAL' AND cron_expression IS NULL)
        OR
        (schedule_type = 'CRON'
            AND cron_expression IS NOT NULL
            AND CHAR_LENGTH(TRIM(cron_expression)) > 0)
    ),
    CONSTRAINT ck_job_timeout CHECK (timeout_seconds > 0),
    CONSTRAINT ck_job_attempts CHECK (max_attempts >= 1),
    CONSTRAINT ck_job_retry_interval CHECK (retry_interval_seconds > 0),
    CONSTRAINT ck_job_misfire CHECK (misfire_policy IN ('SKIP', 'FIRE_ONCE')),
    CONSTRAINT ck_job_version CHECK (config_version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Admin 任务定义及执行策略';
