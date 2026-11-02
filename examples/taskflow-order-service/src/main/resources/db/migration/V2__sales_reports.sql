CREATE TABLE sales_shop (
    id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE sales_product (
    sku_id BIGINT PRIMARY KEY, shop_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
    unit_price_cents BIGINT NOT NULL, CHECK (unit_price_cents > 0)
) ENGINE=InnoDB;
CREATE TABLE order_sale_snapshot (
    order_id VARCHAR(36) PRIMARY KEY, shop_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL, unit_price_cents BIGINT NOT NULL, total_amount_cents BIGINT NOT NULL,
    CHECK (unit_price_cents > 0), CHECK (total_amount_cents > 0)
) ENGINE=InnoDB;
CREATE INDEX ix_sale_shop_order ON order_sale_snapshot(shop_id, order_id);
CREATE INDEX ix_order_paid ON purchase_order(state, paid_at, id);
CREATE TABLE sales_report_request (
    request_key VARCHAR(100) PRIMARY KEY, shop_id BIGINT NOT NULL,
    requested_date DATE, business_date DATE NOT NULL, time_zone VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE sales_report (
    id VARCHAR(36) PRIMARY KEY, shop_id BIGINT NOT NULL, business_date DATE NOT NULL,
    time_zone VARCHAR(100) NOT NULL, order_count BIGINT NOT NULL, total_quantity BIGINT NOT NULL,
    total_amount_cents BIGINT NOT NULL, generated_at TIMESTAMP(6) NOT NULL,
    UNIQUE (shop_id, business_date, time_zone)
) ENGINE=InnoDB;
CREATE TABLE sales_report_item (
    report_id VARCHAR(36) NOT NULL, sku_id BIGINT NOT NULL, product_name VARCHAR(100) NOT NULL,
    order_count BIGINT NOT NULL, total_quantity BIGINT NOT NULL, total_amount_cents BIGINT NOT NULL,
    PRIMARY KEY (report_id, sku_id)
) ENGINE=InnoDB;
CREATE TABLE sales_sample_batch (
    dataset VARCHAR(64) PRIMARY KEY, start_date DATE NOT NULL, days INT NOT NULL,
    order_count INT NOT NULL, seed BIGINT NOT NULL
) ENGINE=InnoDB;
