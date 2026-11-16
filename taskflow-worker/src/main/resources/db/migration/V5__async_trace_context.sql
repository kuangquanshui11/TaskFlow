ALTER TABLE worker_execution ADD COLUMN traceparent VARCHAR(512);
ALTER TABLE worker_execution ADD COLUMN tracestate VARCHAR(512);
