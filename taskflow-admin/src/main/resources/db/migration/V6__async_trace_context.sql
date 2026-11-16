ALTER TABLE job_sync_outbox ADD COLUMN traceparent VARCHAR(512);
ALTER TABLE job_sync_outbox ADD COLUMN tracestate VARCHAR(512);
