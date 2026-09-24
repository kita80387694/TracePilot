CREATE TABLE diagnosis_task (
 id CHAR(36) PRIMARY KEY, owner VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL, request_json JSON NOT NULL, state_json JSON NOT NULL,
 report_json JSON NULL, tool_calls INT NOT NULL DEFAULT 0, model_calls INT NOT NULL DEFAULT 0,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 lease_token CHAR(36) NULL, lease_until TIMESTAMP(6) NULL,
 started_at TIMESTAMP(6) NULL, deadline TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 INDEX ix_claim(status, lease_until, created_at),
 CHECK (tool_calls BETWEEN 0 AND 12), CHECK (model_calls BETWEEN 0 AND 8)
) ENGINE=InnoDB;
CREATE TABLE diagnosis_step (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id CHAR(36) NOT NULL,
 kind VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, detail_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES diagnosis_task(id), INDEX ix_steps(task_id,id)
) ENGINE=InnoDB;
CREATE TABLE diagnosis_evidence (
 id CHAR(36) PRIMARY KEY, task_id CHAR(36) NOT NULL,
 source VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL,
 start_at VARCHAR(40) NOT NULL, end_at VARCHAR(40) NOT NULL,
 locator_json JSON NOT NULL, payload_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES diagnosis_task(id), INDEX ix_evidence(task_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE diagnosis_queue_guard (id INT PRIMARY KEY);
INSERT INTO diagnosis_queue_guard VALUES (1);
