CREATE TABLE order_payment (
    order_id VARCHAR(36) PRIMARY KEY,
    trade_no CHAR(32) NOT NULL UNIQUE,
    app_id VARCHAR(32) NOT NULL,
    merchant_id VARCHAR(32) NOT NULL,
    description VARCHAR(127) NOT NULL,
    amount_cents INT NOT NULL CHECK (amount_cents > 0),
    payment_state VARCHAR(24) NOT NULL,
    code_url VARCHAR(1024),
    transaction_id VARCHAR(32) UNIQUE,
    paid_at TIMESTAMP(6),
    refund_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    refund_id VARCHAR(32),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES purchase_order(id)
) ENGINE=InnoDB;

CREATE TABLE order_payment_outbox (
    order_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    next_at TIMESTAMP(6) NOT NULL,
    stop_at TIMESTAMP(6) NOT NULL,
    claim_token VARCHAR(36),
    last_error VARCHAR(64),
    PRIMARY KEY (order_id, kind),
    CONSTRAINT fk_payment_work_order FOREIGN KEY (order_id) REFERENCES purchase_order(id)
) ENGINE=InnoDB;
CREATE INDEX ix_order_payment_outbox_due ON order_payment_outbox(kind, state, next_at, order_id);
