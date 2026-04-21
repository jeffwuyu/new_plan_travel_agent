-- 中文注释：数据库初始化脚本，用于创建表结构并准备项目运行所需的基础数据。
-- Travel Agent Database Schema
-- Execute this script to initialize the database.
-- MySQL 8.0+ required.

CREATE DATABASE IF NOT EXISTS travel_agent
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE travel_agent;

-- =============================================================
-- Users & Quota
-- =============================================================

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT          NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)     NOT NULL,
    email         VARCHAR(128)    NOT NULL,
    password_hash VARCHAR(128)    NOT NULL,
    user_level    TINYINT         NOT NULL DEFAULT 1  COMMENT '1=REGULAR, 2=VIP, 3=ADMIN',
    status        TINYINT         NOT NULL DEFAULT 1  COMMENT '1=active, 0=disabled',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    DATETIME        NULL     DEFAULT NULL COMMENT 'NULL means not deleted (soft delete)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User accounts';


CREATE TABLE IF NOT EXISTS user_quota_config (
    id                   BIGINT   NOT NULL AUTO_INCREMENT,
    user_level           TINYINT  NOT NULL COMMENT '1=REGULAR, 2=VIP, 3=ADMIN',
    daily_token_limit    INT      NOT NULL DEFAULT 10000,
    monthly_token_limit  INT      NOT NULL DEFAULT 100000,
    max_concurrent_tasks INT      NOT NULL DEFAULT 2,
    max_plan_steps       INT      NOT NULL DEFAULT 15,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_level (user_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-level quota configuration';


CREATE TABLE IF NOT EXISTS user_quota_usage (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    period_type VARCHAR(8)  NOT NULL COMMENT 'daily | monthly',
    period_key  VARCHAR(16) NOT NULL COMMENT '2026-04-15 or 2026-04',
    tokens_used INT         NOT NULL DEFAULT 0,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_period (user_id, period_type, period_key),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Periodic token usage snapshot (Redis is primary)';


CREATE TABLE IF NOT EXISTS user_sessions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(512) NOT NULL COMMENT 'SHA-256 of JWT token',
    ip_address  VARCHAR(45)  NULL,
    user_agent  VARCHAR(512) NULL,
    expires_at  DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    INDEX idx_user_id (user_id),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Active sessions for admin monitoring (JWT blacklist is in Redis)';


-- =============================================================
-- Tasks & Plans
-- =============================================================

CREATE TABLE IF NOT EXISTS tasks (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    task_uuid        VARCHAR(36)  NOT NULL COMMENT 'UUID used in public APIs',
    user_id          BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending'
                     COMMENT 'pending|planning|tool_calling|paused|resuming|completed|failed|cancelled',
    region           VARCHAR(128) NULL     COMMENT 'Target region, e.g. 西安市',
    checkpoint_json  MEDIUMTEXT   NULL     COMMENT 'JSON serialized TaskCheckpoint',
    schema_version   VARCHAR(8)   NOT NULL DEFAULT '1.0',
    total_tokens_used INT         NOT NULL DEFAULT 0,
    error_message    TEXT         NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at     DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_uuid (task_uuid),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task records with checkpoint support';


CREATE TABLE IF NOT EXISTS plans (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    task_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(255) NULL,
    region     VARCHAR(128) NULL,
    summary    TEXT         NULL COMMENT 'LLM-generated narrative summary',
    total_days INT          NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Completed travel plans';


CREATE TABLE IF NOT EXISTS plan_steps (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    plan_id                BIGINT        NOT NULL,
    step_order             INT           NOT NULL COMMENT '0-based execution order',
    day_number             INT           NOT NULL COMMENT '1-based day number',
    attraction_name        VARCHAR(255)  NULL,
    latitude               DECIMAL(10,7) NULL,
    longitude              DECIMAL(10,7) NULL,
    estimated_duration_min INT           NULL     COMMENT 'Estimated visit time in minutes',
    traffic_time_from_prev INT           NULL     COMMENT 'Travel time from previous step in minutes',
    weather_note           VARCHAR(512)  NULL,
    llm_description        TEXT          NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_plan_id (plan_id),
    UNIQUE KEY uk_plan_step_order (plan_id, step_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ordered steps within a travel plan';


-- =============================================================
-- Attractions (local Amap cache)
-- =============================================================

CREATE TABLE IF NOT EXISTS attractions (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    amap_poi_id  VARCHAR(64)   NULL COMMENT 'Amap POI ID',
    name         VARCHAR(255)  NOT NULL,
    region       VARCHAR(128)  NULL,
    city         VARCHAR(128)  NULL,
    district     VARCHAR(128)  NULL,
    category     VARCHAR(128)  NULL,
    sub_category VARCHAR(128)  NULL,
    latitude     DECIMAL(10,7) NOT NULL,
    longitude    DECIMAL(10,7) NOT NULL,
    address      VARCHAR(512)  NULL,
    rating       DECIMAL(3,1)  NULL,
    description  TEXT          NULL,
    tags_json    TEXT          NULL,
    price_level  INT           NULL,
    visit_duration_min INT     NULL,
    open_hours_json TEXT       NULL,
    best_visit_time_json TEXT  NULL,
    crowd_level  VARCHAR(32)   NULL,
    transport_access_json TEXT NULL,
    suitable_for_json TEXT     NULL,
    physical_intensity VARCHAR(32) NULL,
    reservation_required BOOLEAN NULL,
    popularity_score DECIMAL(6,3) NULL,
    style_embedding_id VARCHAR(128) NULL,
    style_embedding_json MEDIUMTEXT NULL,
    source       VARCHAR(64)   NULL,
    dashvector_id VARCHAR(128) NULL COMMENT 'DashVector embedding ID',
    cached_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_amap_poi_id (amap_poi_id),
    INDEX idx_region (region),
    INDEX idx_region_category (region, category),
    INDEX idx_city_district (city, district),
    INDEX idx_lat_lng (latitude, longitude),
    INDEX idx_popularity_score (popularity_score),
    INDEX idx_cached_at (cached_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cached attraction/POI data from Amap';


-- =============================================================
-- LLM Audit Logs
-- =============================================================

CREATE TABLE IF NOT EXISTS llm_call_logs (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    task_id           BIGINT       NULL,
    user_id           BIGINT       NOT NULL,
    call_type         VARCHAR(32)  NULL COMMENT 'planning|tool_call|embedding|history_compress',
    model             VARCHAR(64)  NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    latency_ms        INT          NULL,
    status            VARCHAR(16)  NULL COMMENT 'success|error|timeout',
    idempotency_key   VARCHAR(128) NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_id (task_id),
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM API call audit log';


-- =============================================================
-- RAG Knowledge Base
-- =============================================================

CREATE TABLE IF NOT EXISTS rag_documents (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    oss_key       VARCHAR(512) NOT NULL COMMENT 'OSS object path',
    title         VARCHAR(255) NULL,
    region        VARCHAR(128) NULL,
    doc_type      VARCHAR(32)  NULL COMMENT 'pdf|markdown|text',
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|indexed|failed',
    error_message TEXT         NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oss_key (oss_key),
    INDEX idx_status (status),
    INDEX idx_region (region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG source documents stored in OSS';


CREATE TABLE IF NOT EXISTS rag_chunks (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    document_id    BIGINT       NOT NULL,
    chunk_index    INT          NOT NULL COMMENT '0-based chunk position within document',
    chunk_text     TEXT         NOT NULL,
    dashvector_id  VARCHAR(128) NULL COMMENT 'DashVector vector ID',
    token_count    INT          NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_document_id (document_id),
    INDEX idx_dashvector_id (dashvector_id),
    UNIQUE KEY uk_doc_chunk (document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Text chunks with DashVector IDs for RAG retrieval';


-- =============================================================
-- Initial data: quota configs
-- =============================================================

INSERT IGNORE INTO user_quota_config
    (user_level, daily_token_limit, monthly_token_limit, max_concurrent_tasks, max_plan_steps)
VALUES
    (1, 10000,  100000,  2,  15),   -- REGULAR
    (2, 50000,  500000,  5,  30),   -- VIP
    (3, 999999, 9999999, 10, 50);   -- ADMIN


-- =============================================================
-- Task Execution Event Log (P1-3)
-- =============================================================

CREATE TABLE IF NOT EXISTS task_execution_events (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    task_uuid    VARCHAR(36)   NOT NULL,
    event_type   VARCHAR(32)   NOT NULL COMMENT 'STATE_CHANGE|TOOL_START|TOOL_DONE|STEP_DONE|ERROR|RETRY|PAUSED|COMPLETED',
    status       VARCHAR(32)   NULL     COMMENT 'task status at event time',
    step_index   INT           NULL,
    total_steps  INT           NULL,
    message      VARCHAR(512)  NULL,
    details_json TEXT          NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_uuid (task_uuid),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent execution event log for agent tasks';
