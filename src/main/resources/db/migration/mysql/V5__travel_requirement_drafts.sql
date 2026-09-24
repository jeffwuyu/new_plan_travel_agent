CREATE TABLE IF NOT EXISTS travel_requirement_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL,
    raw_text TEXT NOT NULL,
    constraints_json MEDIUMTEXT NOT NULL,
    questions_json MEDIUMTEXT NULL,
    idempotency_key VARCHAR(128) NULL,
    task_uuid VARCHAR(36) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_travel_requirement_idempotency (user_id, idempotency_key),
    INDEX idx_travel_requirement_owner (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
