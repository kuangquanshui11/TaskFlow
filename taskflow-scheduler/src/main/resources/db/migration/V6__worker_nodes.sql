CREATE TABLE worker_node (
    worker_id VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    registration_fingerprint VARCHAR(64) NOT NULL,
    handler_keys_json LONGTEXT NOT NULL,
    parallelism INT NOT NULL,
    max_pending INT NOT NULL,
    running_count INT NOT NULL,
    pending_count INT NOT NULL,
    dispatch_transport VARCHAR(10) NOT NULL,
    dispatch_vhost VARCHAR(100),
    dispatch_exchange VARCHAR(200),
    dispatch_queue VARCHAR(200),
    dispatch_routing_key VARCHAR(200),
    last_sequence BIGINT NOT NULL,
    registered_at TIMESTAMP(3) NOT NULL,
    last_heartbeat_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uq_worker_queue UNIQUE(dispatch_vhost, dispatch_queue)
);
CREATE INDEX idx_worker_expiry ON worker_node(expires_at);
CREATE TABLE worker_node_session (
    session_id VARCHAR(36) PRIMARY KEY,
    worker_id VARCHAR(100) NOT NULL,
    generation BIGINT NOT NULL,
    registered_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uq_worker_generation UNIQUE(worker_id, generation)
);
