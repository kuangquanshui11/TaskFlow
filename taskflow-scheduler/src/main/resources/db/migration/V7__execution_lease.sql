ALTER TABLE run_attempt ADD COLUMN lease_until TIMESTAMP(3);
ALTER TABLE run_attempt ADD COLUMN owner_worker_id VARCHAR(100);
ALTER TABLE run_attempt ADD COLUMN owner_session_id VARCHAR(36);
CREATE INDEX idx_attempt_live_lease ON run_attempt(state, lease_until);
