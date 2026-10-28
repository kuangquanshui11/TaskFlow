CREATE TABLE dispatch_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL,
    message_json LONGTEXT,
    expires_at TIMESTAMP(3),
    publish_attempts INT NOT NULL,
    next_publish_at TIMESTAMP(3) NOT NULL,
    claim_version BIGINT NOT NULL,
    claim_until TIMESTAMP(3),
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(3) NOT NULL,
    published_at TIMESTAMP(3),
    CONSTRAINT ck_dispatch_state CHECK (state IN ('WAITING','READY','SENDING','PUBLISHED','FAILED','CANCELLED'))
);
CREATE INDEX idx_outbox_publish ON dispatch_outbox(state, next_publish_at, claim_until);

-- 升级前尚未确认的派发仍可恢复；消息准备时沿用已存在的 Attempt 与租约。
INSERT INTO dispatch_outbox (event_id,attempt_id,state,publish_attempts,next_publish_at,claim_version,created_at)
SELECT id,id,'WAITING',0,next_dispatch_at,0,CURRENT_TIMESTAMP(3)
FROM run_attempt WHERE state IN ('READY','DISPATCHING');
