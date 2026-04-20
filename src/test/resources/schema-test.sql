DROP TABLE IF EXISTS tasks;
DROP TABLE IF EXISTS user_quota_config;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    email         VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    user_level    INT NOT NULL DEFAULT 1,
    status        INT NOT NULL DEFAULT 1,
    created_at    DATETIME NOT NULL DEFAULT NOW(),
    updated_at    DATETIME NOT NULL DEFAULT NOW(),
    deleted_at    DATETIME
);

CREATE TABLE tasks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid         VARCHAR(64) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    region            VARCHAR(128),
    checkpoint_json   TEXT,
    schema_version    VARCHAR(16),
    total_tokens_used INT NOT NULL DEFAULT 0,
    error_message     VARCHAR(1024),
    created_at        DATETIME NOT NULL DEFAULT NOW(),
    updated_at        DATETIME NOT NULL DEFAULT NOW(),
    completed_at      DATETIME
);

CREATE TABLE user_quota_config (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_level           INT NOT NULL UNIQUE,
    daily_token_limit    INT NOT NULL DEFAULT 10000,
    monthly_token_limit  INT NOT NULL DEFAULT 100000,
    max_concurrent_tasks INT NOT NULL DEFAULT 2,
    max_plan_steps       INT NOT NULL DEFAULT 15,
    created_at           DATETIME NOT NULL DEFAULT NOW(),
    updated_at           DATETIME NOT NULL DEFAULT NOW()
);
