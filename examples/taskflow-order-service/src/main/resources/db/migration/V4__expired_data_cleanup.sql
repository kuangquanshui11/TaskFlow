CREATE TABLE stock_outbox_history (
  id VARCHAR(36) PRIMARY KEY, sku_id BIGINT NOT NULL, published_at TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB;
CREATE INDEX ix_stock_outbox_history_expiry ON stock_outbox_history(published_at, id);

CREATE TABLE expired_data_cleanup_run (
  request_key VARCHAR(100) PRIMARY KEY, cutoff TIMESTAMP(6) NOT NULL,
  retention_days INT NOT NULL, batch_size INT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE expired_data_cleanup_batch (
  request_key VARCHAR(100) NOT NULL, batch_number INT NOT NULL,
  deleted_count INT NOT NULL, has_more BOOLEAN NOT NULL,
  PRIMARY KEY (request_key, batch_number)
) ENGINE=InnoDB;
