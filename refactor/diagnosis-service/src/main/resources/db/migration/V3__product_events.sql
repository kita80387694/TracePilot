ALTER TABLE diagnosis_task ADD COLUMN parent_id CHAR(36) NULL,
 ADD COLUMN feedback_json JSON NULL,
 ADD INDEX ix_owner_history(owner,created_at,id);
CREATE TABLE diagnosis_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id CHAR(36) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 payload_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES diagnosis_task(id),
 INDEX ix_task_event(task_id,id)
) ENGINE=InnoDB;
