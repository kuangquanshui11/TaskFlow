ALTER TABLE run_execution ADD COLUMN traceparent VARCHAR(512);
ALTER TABLE run_execution ADD COLUMN tracestate VARCHAR(512);

ALTER TABLE dispatch_outbox ADD COLUMN traceparent VARCHAR(512);
ALTER TABLE dispatch_outbox ADD COLUMN tracestate VARCHAR(512);
