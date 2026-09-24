CREATE TABLE IF NOT EXISTS tool_execution_records (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    task_uuid            VARCHAR(36)  NOT NULL,
    user_id              BIGINT       NULL,
    tool_name            VARCHAR(128) NOT NULL,
    idempotency_key      VARCHAR(255) NOT NULL,
    argument_fingerprint CHAR(64)     NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    result_json          MEDIUMTEXT   NULL,
    error_message        TEXT         NULL,
    started_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at         DATETIME     NULL,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_execution_idempotency (task_uuid, idempotency_key),
    INDEX idx_tool_execution_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable tool idempotency and execution outcome records';
