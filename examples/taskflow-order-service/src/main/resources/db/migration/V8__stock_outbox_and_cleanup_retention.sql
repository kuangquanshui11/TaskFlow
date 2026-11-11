ALTER TABLE stock_outbox ADD COLUMN delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE stock_outbox ADD COLUMN generation BIGINT NOT NULL DEFAULT 0;
CREATE INDEX ix_stock_outbox_delivery ON stock_outbox(delivery_state, next_attempt_at, created_at, id);
CREATE INDEX ix_stock_outbox_exhaustion ON stock_outbox(delivery_state, attempts, id);

CREATE TABLE stock_outbox_failure (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(36) NOT NULL,
  generation BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  attempts INT NOT NULL,
  detected_at TIMESTAMP(6) NOT NULL,
  last_failed_at TIMESTAMP(6) NULL,
  error_code VARCHAR(40) NOT NULL,
  retry_requested_at TIMESTAMP(6) NULL,
  retry_reason VARCHAR(250) NULL,
  resolved_at TIMESTAMP(6) NULL,
  UNIQUE (event_id, generation)
) ENGINE=InnoDB;
CREATE INDEX ix_stock_outbox_failure_retention ON stock_outbox_failure(resolved_at, id);

ALTER TABLE expired_data_cleanup_run MODIFY COLUMN cutoff TIMESTAMP(6) NULL;
ALTER TABLE expired_data_cleanup_run MODIFY COLUMN retention_days INT NULL;
ALTER TABLE expired_data_cleanup_run MODIFY COLUMN batch_size INT NULL;
ALTER TABLE expired_data_cleanup_run ADD COLUMN last_activity_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE expired_data_cleanup_run ADD COLUMN expired BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE expired_data_cleanup_run SET last_activity_at = CURRENT_TIMESTAMP(6);
CREATE INDEX ix_cleanup_run_retention ON expired_data_cleanup_run(expired, last_activity_at, request_key);
