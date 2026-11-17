ALTER TABLE job_definition ADD COLUMN retry_policy VARCHAR(16);
ALTER TABLE job_definition ADD COLUMN retry_max_interval_seconds INT;
