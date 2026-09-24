ALTER TABLE outbox
 ADD COLUMN source_request_id VARCHAR(36) NULL,
 ADD COLUMN source_trace_id CHAR(32) NULL,
 ADD COLUMN source_version VARCHAR(100) NULL;
CREATE INDEX idx_outbox_age ON outbox(status,created_at);
