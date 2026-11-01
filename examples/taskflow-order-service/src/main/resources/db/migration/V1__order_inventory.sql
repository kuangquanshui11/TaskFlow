CREATE TABLE sku_stock (
  sku_id BIGINT PRIMARY KEY, available BIGINT NOT NULL, reserved BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK (available >= 0), CHECK (reserved >= 0)
) ENGINE=InnoDB;
CREATE TABLE purchase_order (
  id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(100) NOT NULL UNIQUE,
  sku_id BIGINT NOT NULL, quantity INT NOT NULL, state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
  paid_at TIMESTAMP(6), closed_at TIMESTAMP(6), close_reason VARCHAR(64),
  CHECK (quantity > 0)
) ENGINE=InnoDB;
CREATE INDEX ix_order_expiry ON purchase_order(state, expires_at, id);
CREATE TABLE stock_reservation (
  order_id VARCHAR(36) PRIMARY KEY, sku_id BIGINT NOT NULL, quantity INT NOT NULL, state VARCHAR(16) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE stock_ledger (
  id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, sku_id BIGINT NOT NULL,
  operation VARCHAR(16) NOT NULL, quantity INT NOT NULL, request_id VARCHAR(128), created_at TIMESTAMP(6) NOT NULL,
  UNIQUE (order_id, operation)
) ENGINE=InnoDB;
CREATE TABLE stock_outbox (
  id VARCHAR(36) PRIMARY KEY, sku_id BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
  attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB;
CREATE INDEX ix_outbox_pending ON stock_outbox(next_attempt_at, created_at);
