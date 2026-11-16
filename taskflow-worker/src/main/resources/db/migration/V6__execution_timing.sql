ALTER TABLE worker_execution ADD COLUMN handler_elapsed_millis BIGINT;
ALTER TABLE worker_execution ADD COLUMN result_recorded_at TIMESTAMP(3);
ALTER TABLE worker_execution ADD COLUMN result_acknowledged_at TIMESTAMP(3);
