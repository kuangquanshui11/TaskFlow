CREATE TABLE run_outcome_case (
    attempt_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    detected_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_outcome_case_attempt FOREIGN KEY (attempt_id) REFERENCES run_attempt(id) ON DELETE CASCADE
);
CREATE INDEX idx_outcome_case_run ON run_outcome_case(run_id, detected_at);

CREATE TABLE run_outcome_review (
    review_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(36) NOT NULL UNIQUE,
    attempt_id VARCHAR(36) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    evidence VARCHAR(500) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    reviewed_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_outcome_review_case FOREIGN KEY (attempt_id) REFERENCES run_outcome_case(attempt_id) ON DELETE CASCADE
);
CREATE INDEX idx_outcome_review_attempt ON run_outcome_review(attempt_id, review_id);

INSERT INTO run_outcome_case(attempt_id,run_id,reason,detected_at)
SELECT id,run_id,result_code,finished_at FROM run_attempt
WHERE finished_at IS NOT NULL AND (
    result_code IN ('WORKER_RESTARTED','RESULT_OUTCOME_UNKNOWN')
    OR (owner_worker_id IS NOT NULL AND result_code IN ('LEASE_EXPIRED','LEASE_LOST','EXECUTION_TIMEOUT','HANDLER_INTERRUPTED'))
);
