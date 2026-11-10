CREATE TABLE sales_report_revision (
    id VARCHAR(36) PRIMARY KEY, shop_id BIGINT NOT NULL, business_date DATE NOT NULL,
    time_zone VARCHAR(100) NOT NULL, revision BIGINT NOT NULL, revision_reason VARCHAR(200),
    order_count BIGINT NOT NULL, total_quantity BIGINT NOT NULL, total_amount_cents BIGINT NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    UNIQUE (shop_id, business_date, time_zone, revision)
) ENGINE=InnoDB;
CREATE INDEX ix_report_revision_date ON sales_report_revision(business_date, shop_id, id);

INSERT INTO sales_report_revision
    (id, shop_id, business_date, time_zone, revision, order_count, total_quantity, total_amount_cents, generated_at)
SELECT id, shop_id, business_date, time_zone, 1, order_count, total_quantity, total_amount_cents, generated_at
FROM sales_report;

ALTER TABLE sales_report_request ADD COLUMN revision_reason VARCHAR(200);
ALTER TABLE sales_report_request ADD COLUMN report_id VARCHAR(36);
UPDATE sales_report_request SET report_id = (
    SELECT id FROM sales_report
    WHERE shop_id = sales_report_request.shop_id
        AND business_date = sales_report_request.business_date AND time_zone = sales_report_request.time_zone
);
DROP TABLE sales_report;

ALTER TABLE sales_product ADD COLUMN catalog_source VARCHAR(64);
ALTER TABLE sales_product ADD COLUMN catalog_version BIGINT;
ALTER TABLE order_sale_snapshot ADD COLUMN source_system VARCHAR(64);
ALTER TABLE order_sale_snapshot ADD COLUMN source_reference VARCHAR(100);
ALTER TABLE order_sale_snapshot ADD CONSTRAINT uq_sale_source UNIQUE (source_system, source_reference);
