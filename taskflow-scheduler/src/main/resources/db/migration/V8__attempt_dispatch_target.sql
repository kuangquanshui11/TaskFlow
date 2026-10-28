ALTER TABLE run_attempt ADD COLUMN target_worker_id VARCHAR(100);
CREATE INDEX idx_attempt_target ON run_attempt(target_worker_id, state);
ALTER TABLE dispatch_outbox ADD COLUMN target_worker_id VARCHAR(100);
ALTER TABLE dispatch_outbox ADD COLUMN target_vhost VARCHAR(100);
ALTER TABLE dispatch_outbox ADD COLUMN target_exchange VARCHAR(200);
ALTER TABLE dispatch_outbox ADD COLUMN target_queue VARCHAR(200);
ALTER TABLE dispatch_outbox ADD COLUMN target_routing_key VARCHAR(200);
-- 同一 exchange/routingKey 不能绑定两个节点，否则 direct exchange 会复制命令。
ALTER TABLE worker_node ADD CONSTRAINT uq_worker_route UNIQUE(dispatch_vhost, dispatch_exchange, dispatch_routing_key);
