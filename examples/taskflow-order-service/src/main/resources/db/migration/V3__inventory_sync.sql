CREATE TABLE inventory_sync_warehouse (
  warehouse_id BIGINT PRIMARY KEY,
  source_version BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE inventory_sync_stock (
  warehouse_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  source_version BIGINT NOT NULL,
  PRIMARY KEY (warehouse_id, sku_id),
  CHECK (quantity >= 0)
) ENGINE=InnoDB;

CREATE TABLE inventory_sync_batch (
  id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(100) NOT NULL UNIQUE,
  warehouse_id BIGINT NOT NULL,
  source_version BIGINT NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  updated_count INT NOT NULL,
  CHECK (updated_count >= 0)
) ENGINE=InnoDB;
