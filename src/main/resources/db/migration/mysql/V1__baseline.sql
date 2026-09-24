-- Runtime schema initializer for the configured MySQL database.
-- Safe to execute on every startup.

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT          NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)     NOT NULL,
    email         VARCHAR(128)    NOT NULL,
    password_hash VARCHAR(128)    NOT NULL,
    user_level    TINYINT         NOT NULL DEFAULT 1 COMMENT '1=REGULAR, 2=VIP, 3=ADMIN',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    DATETIME        NULL DEFAULT NULL COMMENT 'NULL means not deleted (soft delete)',
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
    route_map_daily_limit INT     NULL COMMENT 'Daily new route map record limit; NULL falls back to route-map config',
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

CREATE TABLE IF NOT EXISTS user_memory_profile (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    source_summary  VARCHAR(512) NULL COMMENT 'Sanitized latest evidence summary',
    profile_summary TEXT         NULL COMMENT 'Compact generated profile for prompt injection',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_memory_profile_user (user_id),
    INDEX idx_user_memory_profile_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable low-priority long-term user memory profile';

CREATE TABLE IF NOT EXISTS user_memory_fact (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    memory_type     VARCHAR(64)  NOT NULL COMMENT 'preference|avoid|budget|destination|transport|group',
    memory_key      VARCHAR(64)  NOT NULL COMMENT 'category such as attraction_interest or pace',
    memory_value    VARCHAR(255) NOT NULL,
    confidence      DECIMAL(4,2) NOT NULL DEFAULT 0.60,
    evidence_count  INT          NOT NULL DEFAULT 1,
    source          VARCHAR(64)  NULL,
    source_summary  VARCHAR(512) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_memory_fact (user_id, memory_type, memory_key, memory_value),
    INDEX idx_user_memory_fact_user (user_id, deleted_at),
    INDEX idx_user_memory_fact_type (memory_type, memory_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable user memory facts with confidence and evidence';

CREATE TABLE IF NOT EXISTS tasks (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    task_uuid         VARCHAR(36)  NOT NULL COMMENT 'UUID used in public APIs',
    user_id           BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending'
                      COMMENT 'pending|planning|tool_calling|paused|resuming|completed|failed|cancelled',
    region            VARCHAR(128) NULL COMMENT 'Target region',
    request_ip        VARCHAR(45)  NULL COMMENT 'Client IP captured when the task was created',
    checkpoint_json   MEDIUMTEXT   NULL COMMENT 'JSON serialized TaskCheckpoint',
    schema_version    VARCHAR(8)   NOT NULL DEFAULT '2.0',
    total_tokens_used INT          NOT NULL DEFAULT 0,
    error_message     TEXT         NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at      DATETIME     NULL,
    recovery_attempts INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_uuid (task_uuid),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task records with checkpoint support';

CREATE TABLE IF NOT EXISTS plans (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    task_id                BIGINT       NOT NULL,
    user_id                BIGINT       NOT NULL,
    title                  VARCHAR(255) NULL,
    region                 VARCHAR(128) NULL,
    summary                TEXT         NULL COMMENT 'LLM-generated narrative summary',
    total_days             INT          NULL,
    start_location_query   VARCHAR(128) NULL,
    end_location_query     VARCHAR(128) NULL,
    trip_start_time        DATETIME     NULL,
    trip_end_time          DATETIME     NULL,
    full_day_start_time    TIME         NULL,
    full_day_end_time      TIME         NULL,
    destination_buffer_min INT          NULL,
    accommodation_status   VARCHAR(32)  NULL,
    accommodation_failure_reason VARCHAR(512) NULL,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Completed travel plans';

CREATE TABLE IF NOT EXISTS plan_steps (
    id                              BIGINT        NOT NULL AUTO_INCREMENT,
    plan_id                         BIGINT        NOT NULL,
    step_order                      INT           NOT NULL COMMENT '0-based execution order',
    day_number                      INT           NOT NULL COMMENT '1-based day number',
    attraction_name                 VARCHAR(255)  NULL,
    latitude                        DECIMAL(10,7) NULL,
    longitude                       DECIMAL(10,7) NULL,
    estimated_duration_min          INT           NULL COMMENT 'Estimated visit time in minutes',
    traffic_time_from_prev          INT           NULL COMMENT 'Travel time from previous step in minutes',
    traffic_mode_from_prev          VARCHAR(32)   NULL COMMENT 'Agent selected travel mode from previous step',
    selected_route_summary_from_prev VARCHAR(512) NULL COMMENT 'Agent selected route summary from previous step',
    selected_route_geometry_json    MEDIUMTEXT    NULL COMMENT 'Agent selected route geometry from previous step',
    weather_note                    VARCHAR(512)  NULL,
    llm_description                 TEXT          NULL,
    planned_start_time              DATETIME      NULL,
    planned_end_time                DATETIME      NULL,
    travel_time_to_destination_min  INT           NULL,
    created_at                      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_plan_id (plan_id),
    UNIQUE KEY uk_plan_step_order (plan_id, step_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ordered steps within a travel plan';

CREATE TABLE IF NOT EXISTS plan_day_route_maps (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id               BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    day_number            INT          NOT NULL,
    style                 VARCHAR(64)  NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'pending',
    progress_percent      INT          NOT NULL DEFAULT 0,
    skeleton_oss_key      VARCHAR(512) NULL,
    ai_raw_oss_key        VARCHAR(512) NULL,
    final_oss_key         VARCHAR(512) NULL,
    route_geometry_json   MEDIUMTEXT   NULL,
    stops_json            MEDIUMTEXT   NULL,
    segments_json         MEDIUMTEXT   NULL,
    bounds_json           TEXT         NULL,
    model                 VARCHAR(64)  NULL,
    request_id            VARCHAR(128) NULL,
    task_id               VARCHAR(128) NULL,
    retry_count           INT          NOT NULL DEFAULT 0,
    manual_regen_count    INT          NOT NULL DEFAULT 0,
    error_code            VARCHAR(64)  NULL,
    error_message         VARCHAR(512) NULL,
    latency_ms            BIGINT       NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_day_style (plan_id, day_number, style),
    INDEX idx_plan_id (plan_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily AI route maps for travel plans';

CREATE TABLE IF NOT EXISTS plan_route_map_jobs (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    route_map_id          BIGINT       NOT NULL,
    plan_id               BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    day_number            INT          NOT NULL,
    style                 VARCHAR(64)  NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'queued',
    trigger_type          VARCHAR(64)  NULL,
    locked_by             VARCHAR(128) NULL,
    locked_until          DATETIME     NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    max_attempts          INT          NOT NULL DEFAULT 3,
    error_code            VARCHAR(64)  NULL,
    error_message         VARCHAR(512) NULL,
    available_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at            DATETIME     NULL,
    completed_at          DATETIME     NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_route_map_id (route_map_id),
    INDEX idx_status_available (status, available_at),
    INDEX idx_locked_until (locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent route-map generation worker jobs and dead-letter records';

CREATE TABLE IF NOT EXISTS plan_accommodations (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    plan_id               BIGINT        NOT NULL,
    night_number          INT           NOT NULL,
    check_in_date         DATE          NOT NULL,
    check_out_date        DATE          NOT NULL,
    provider              VARCHAR(32)   NOT NULL,
    provider_hotel_id     VARCHAR(128)  NULL,
    name                  VARCHAR(255)  NOT NULL,
    type                  VARCHAR(64)   NULL,
    address               VARCHAR(512)  NULL,
    latitude              DECIMAL(10,7) NULL,
    longitude             DECIMAL(10,7) NULL,
    rating                DECIMAL(4,2)  NULL,
    review_count          INT           NULL,
    price_per_night_yuan  DECIMAL(10,2) NOT NULL,
    currency              VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    distance_meters       INT           NULL,
    score                 DECIMAL(8,4)  NULL,
    reason                VARCHAR(512)  NULL,
    price_fetched_at      DATETIME      NULL,
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_plan_night (plan_id, night_number),
    INDEX idx_provider_hotel (provider, provider_hotel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA accommodation recommendations for plan nights';

CREATE TABLE IF NOT EXISTS attractions (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    amap_poi_id           VARCHAR(64)   NULL COMMENT 'Amap POI ID',
    name                  VARCHAR(255)  NOT NULL,
    region                VARCHAR(128)  NULL,
    city                  VARCHAR(128)  NULL,
    district              VARCHAR(128)  NULL,
    category              VARCHAR(128)  NULL,
    sub_category          VARCHAR(128)  NULL,
    latitude              DECIMAL(10,7) NOT NULL,
    longitude             DECIMAL(10,7) NOT NULL,
    address               VARCHAR(512)  NULL,
    rating                DECIMAL(3,1)  NULL,
    description           TEXT          NULL,
    tags_json             TEXT          NULL,
    price_level           INT           NULL,
    visit_duration_min    INT           NULL,
    open_hours_json       TEXT          NULL,
    best_visit_time_json  TEXT          NULL,
    crowd_level           VARCHAR(32)   NULL,
    transport_access_json TEXT          NULL,
    suitable_for_json     TEXT          NULL,
    physical_intensity    VARCHAR(32)   NULL,
    reservation_required  BOOLEAN       NULL,
    popularity_score      DECIMAL(6,3)  NULL,
    style_embedding_id    VARCHAR(128)  NULL,
    style_embedding_json  MEDIUMTEXT    NULL,
    source                VARCHAR(64)   NULL,
    dashvector_id         VARCHAR(128)  NULL COMMENT 'DashVector embedding ID',
    cached_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at        DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_amap_poi_id (amap_poi_id),
    INDEX idx_region (region),
    INDEX idx_region_category (region, category),
    INDEX idx_city_district (city, district),
    INDEX idx_lat_lng (latitude, longitude),
    INDEX idx_popularity_score (popularity_score),
    INDEX idx_cached_at (cached_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cached attraction/POI data from Amap';

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

CREATE TABLE IF NOT EXISTS rag_documents (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    oss_key       VARCHAR(512) NOT NULL COMMENT 'OSS object path',
    title         VARCHAR(255) NULL,
    region        VARCHAR(128) NULL,
    doc_type      VARCHAR(32)  NULL COMMENT 'pdf|markdown|text',
    source_type   VARCHAR(32)  NOT NULL DEFAULT 'static_knowledge' COMMENT 'static_knowledge|user_preference',
    source_name   VARCHAR(128) NULL COMMENT 'manual|controlled_crawl|profile_summary',
    source_url    VARCHAR(512) NULL,
    metadata_json TEXT         NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|indexing|indexed|failed|disabled',
    ingest_progress INT        NOT NULL DEFAULT 0,
    retry_count   INT          NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    error_message TEXT         NULL,
    disabled_at   DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oss_key (oss_key),
    INDEX idx_status (status),
    INDEX idx_region (region),
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG source documents stored in OSS';

CREATE TABLE IF NOT EXISTS rag_chunks (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    document_id   BIGINT       NOT NULL,
    chunk_index   INT          NOT NULL COMMENT '0-based chunk position within document',
    chunk_text    TEXT         NOT NULL,
    dashvector_id VARCHAR(128) NULL COMMENT 'DashVector vector ID',
    embedding_json MEDIUMTEXT  NULL COMMENT 'JSON vector fallback when pgvector is unavailable',
    search_text   TEXT         NULL COMMENT 'Text used for keyword recall',
    metadata_json TEXT         NULL,
    token_count   INT          NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_document_id (document_id),
    INDEX idx_dashvector_id (dashvector_id),
    UNIQUE KEY uk_doc_chunk (document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Text chunks with DashVector IDs for RAG retrieval';

CREATE TABLE IF NOT EXISTS task_execution_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    task_uuid    VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(32)  NOT NULL COMMENT 'STATE_CHANGE|TOOL_START|TOOL_DONE|STEP_DONE|ERROR|RETRY|PAUSED|COMPLETED',
    status       VARCHAR(32)  NULL COMMENT 'task status at event time',
    step_index   INT          NULL,
    total_steps  INT          NULL,
    message      VARCHAR(512) NULL,
    details_json TEXT         NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_uuid (task_uuid),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent execution event log for agent tasks';

CREATE TABLE IF NOT EXISTS task_checkpoint_artifacts (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    task_uuid      VARCHAR(36)  NOT NULL,
    task_id        BIGINT       NULL,
    artifact_type  VARCHAR(64)  NOT NULL COMMENT 'llm_history|rag_results|summaries|validator_results|user_feedback|failure_reasons|tool_results',
    payload_json   MEDIUMTEXT   NOT NULL,
    item_count     INT          NOT NULL DEFAULT 0,
    schema_version VARCHAR(8)   NOT NULL DEFAULT '2.0',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_checkpoint_artifact (task_uuid, artifact_type),
    INDEX idx_task_checkpoint_artifacts_task_uuid (task_uuid),
    INDEX idx_task_checkpoint_artifacts_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Large non-resume checkpoint artifacts split out of tasks.checkpoint_json';

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    admin_user_id  BIGINT       NOT NULL,
    permission     VARCHAR(64)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(64)  NULL,
    target_id      VARCHAR(128) NULL,
    request_ip     VARCHAR(64)  NULL,
    user_agent     VARCHAR(512) NULL,
    details_json   TEXT         NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_admin_created (admin_user_id, created_at),
    INDEX idx_action_created (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Admin operation audit log';

INSERT IGNORE INTO user_quota_config
    (user_level, daily_token_limit, monthly_token_limit, max_concurrent_tasks, max_plan_steps, route_map_daily_limit)
VALUES
    (1, 10000, 100000, 2, 15, 20),
    (2, 50000, 500000, 5, 30, 50),
    (3, 999999, 9999999, 10, 50, 200);
