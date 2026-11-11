ALTER TABLE worker_execution ADD COLUMN last_report_error_code VARCHAR(64);
ALTER TABLE worker_execution ADD COLUMN last_report_outcome_number INT;
