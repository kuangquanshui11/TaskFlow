ALTER TABLE inventory_sync_warehouse ADD COLUMN source_id VARCHAR(48) NOT NULL DEFAULT 'sample';
ALTER TABLE inventory_sync_warehouse DROP PRIMARY KEY;
ALTER TABLE inventory_sync_warehouse ADD PRIMARY KEY (source_id, warehouse_id);
ALTER TABLE inventory_sync_warehouse ADD COLUMN fingerprint CHAR(64);
ALTER TABLE inventory_sync_warehouse ADD COLUMN last_synced_at TIMESTAMP(6) NULL;
ALTER TABLE inventory_sync_warehouse ADD COLUMN last_failure_at TIMESTAMP(6) NULL;
ALTER TABLE inventory_sync_warehouse ADD COLUMN last_failure_code VARCHAR(64);
ALTER TABLE inventory_sync_warehouse ADD COLUMN last_failure_request_key VARCHAR(100);

ALTER TABLE inventory_sync_stock ADD COLUMN source_id VARCHAR(48) NOT NULL DEFAULT 'sample';
ALTER TABLE inventory_sync_stock DROP PRIMARY KEY;
ALTER TABLE inventory_sync_stock ADD PRIMARY KEY (source_id, warehouse_id, sku_id);

ALTER TABLE inventory_sync_batch ADD COLUMN source_id VARCHAR(48) NOT NULL DEFAULT 'sample';
