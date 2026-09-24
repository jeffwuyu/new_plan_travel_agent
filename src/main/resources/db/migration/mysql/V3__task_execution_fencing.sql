-- Additive task fencing and lifecycle operation storage.
-- Existing tasks start at revision 1 and remain readable by older application builds.
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS lease_expires_at DATETIME NULL,
    ADD COLUMN IF NOT EXISTS execution_owner VARCHAR(128) NULL;

CREATE TABLE IF NOT EXISTS task_operations (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    task_uuid      VARCHAR(36)  NOT NULL,
    operation_id   VARCHAR(128) NOT NULL,
    operation_type VARCHAR(64)  NOT NULL,
    result_status  VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    result_json    MEDIUMTEXT   NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_operation (user_id, task_uuid, operation_id),
    INDEX idx_task_operation_task (task_uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent task lifecycle operation results';
