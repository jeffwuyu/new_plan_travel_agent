DROP TABLE IF EXISTS task_checkpoint_artifacts;
DROP TABLE IF EXISTS task_execution_events;
DROP TABLE IF EXISTS admin_audit_logs;
DROP TABLE IF EXISTS user_memory_fact;
DROP TABLE IF EXISTS user_memory_profile;
DROP TABLE IF EXISTS rag_chunks;
DROP TABLE IF EXISTS rag_documents;
DROP TABLE IF EXISTS plan_accommodations;
DROP TABLE IF EXISTS plan_route_map_jobs;
DROP TABLE IF EXISTS plan_day_route_maps;
DROP TABLE IF EXISTS plan_steps;
DROP TABLE IF EXISTS plans;
DROP TABLE IF EXISTS attractions;
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

CREATE TABLE user_memory_profile (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
    source_summary  VARCHAR(512),
    profile_summary CLOB,
    created_at      DATETIME NOT NULL DEFAULT NOW(),
    updated_at      DATETIME NOT NULL DEFAULT NOW(),
    deleted_at      DATETIME
);

CREATE TABLE user_memory_fact (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    memory_type     VARCHAR(64) NOT NULL,
    memory_key      VARCHAR(64) NOT NULL,
    memory_value    VARCHAR(255) NOT NULL,
    confidence      DECIMAL(4,2) NOT NULL DEFAULT 0.60,
    evidence_count  INT NOT NULL DEFAULT 1,
    source          VARCHAR(64),
    source_summary  VARCHAR(512),
    created_at      DATETIME NOT NULL DEFAULT NOW(),
    updated_at      DATETIME NOT NULL DEFAULT NOW(),
    deleted_at      DATETIME,
    CONSTRAINT uk_user_memory_fact UNIQUE (user_id, memory_type, memory_key, memory_value)
);

CREATE TABLE tasks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid         VARCHAR(64) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    region            VARCHAR(128),
    request_ip        VARCHAR(45),
    checkpoint_json   TEXT,
    schema_version    VARCHAR(16),
    total_tokens_used INT NOT NULL DEFAULT 0,
    error_message     VARCHAR(1024),
    created_at        DATETIME NOT NULL DEFAULT NOW(),
    updated_at        DATETIME NOT NULL DEFAULT NOW(),
    completed_at      DATETIME,
    recovery_attempts INT NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS tool_execution_records;
CREATE TABLE tool_execution_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid VARCHAR(64) NOT NULL,
    user_id BIGINT,
    tool_name VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    argument_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    result_json CLOB,
    error_message CLOB,
    started_at DATETIME NOT NULL DEFAULT NOW(),
    completed_at DATETIME,
    updated_at DATETIME NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_tool_execution_idempotency UNIQUE (task_uuid, idempotency_key)
);


CREATE TABLE attractions (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    amap_poi_id           VARCHAR(64),
    name                  VARCHAR(255) NOT NULL,
    region                VARCHAR(128),
    city                  VARCHAR(128),
    district              VARCHAR(128),
    category              VARCHAR(128),
    sub_category          VARCHAR(128),
    latitude              DECIMAL(10,7) NOT NULL,
    longitude             DECIMAL(10,7) NOT NULL,
    address               VARCHAR(512),
    rating                DECIMAL(3,1),
    description           CLOB,
    tags_json             CLOB,
    price_level           INT,
    visit_duration_min    INT,
    open_hours_json       CLOB,
    best_visit_time_json  CLOB,
    crowd_level           VARCHAR(32),
    transport_access_json CLOB,
    suitable_for_json     CLOB,
    physical_intensity    VARCHAR(32),
    reservation_required  BOOLEAN,
    popularity_score      DECIMAL(6,3),
    style_embedding_id    VARCHAR(128),
    style_embedding_json  CLOB,
    source                VARCHAR(64),
    dashvector_id         VARCHAR(128),
    cached_at             DATETIME NOT NULL DEFAULT NOW(),
    last_synced_at        DATETIME,
    CONSTRAINT uk_amap_poi_id UNIQUE (amap_poi_id)
);

CREATE TABLE plans (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    title      VARCHAR(255),
    region     VARCHAR(128),
    summary    CLOB,
    total_days INT,
    start_location_query VARCHAR(128),
    end_location_query VARCHAR(128),
    trip_start_time DATETIME,
    trip_end_time DATETIME,
    full_day_start_time TIME,
    full_day_end_time TIME,
    destination_buffer_min INT,
    accommodation_status VARCHAR(32),
    accommodation_failure_reason VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE plan_steps (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                BIGINT NOT NULL,
    step_order             INT NOT NULL,
    day_number             INT NOT NULL,
    attraction_name        VARCHAR(255),
    latitude               DECIMAL(10,7),
    longitude              DECIMAL(10,7),
    estimated_duration_min INT,
    traffic_time_from_prev INT,
    weather_note           VARCHAR(512),
    llm_description        CLOB,
    planned_start_time     DATETIME,
    planned_end_time       DATETIME,
    travel_time_to_destination_min INT,
    created_at             DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE plan_accommodations (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id               BIGINT NOT NULL,
    night_number          INT NOT NULL,
    check_in_date         DATE NOT NULL,
    check_out_date        DATE NOT NULL,
    provider              VARCHAR(32) NOT NULL,
    provider_hotel_id     VARCHAR(128),
    name                  VARCHAR(255) NOT NULL,
    type                  VARCHAR(64),
    address               VARCHAR(512),
    latitude              DECIMAL(10,7),
    longitude             DECIMAL(10,7),
    rating                DECIMAL(4,2),
    review_count          INT,
    price_per_night_yuan  DECIMAL(10,2) NOT NULL,
    currency              VARCHAR(8) NOT NULL DEFAULT 'CNY',
    distance_meters       INT,
    score                 DECIMAL(8,4),
    reason                VARCHAR(512),
    price_fetched_at      DATETIME,
    created_at            DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE plan_day_route_maps (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id               BIGINT NOT NULL,
    user_id               BIGINT NOT NULL,
    day_number            INT NOT NULL,
    style                 VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress_percent      INT NOT NULL DEFAULT 0,
    skeleton_oss_key      VARCHAR(512),
    ai_raw_oss_key        VARCHAR(512),
    final_oss_key         VARCHAR(512),
    route_geometry_json   CLOB,
    stops_json            CLOB,
    segments_json         CLOB,
    bounds_json           CLOB,
    model                 VARCHAR(64),
    request_id            VARCHAR(128),
    task_id               VARCHAR(128),
    retry_count           INT NOT NULL DEFAULT 0,
    manual_regen_count    INT NOT NULL DEFAULT 0,
    error_code            VARCHAR(64),
    error_message         VARCHAR(512),
    latency_ms            BIGINT,
    created_at            DATETIME NOT NULL DEFAULT NOW(),
    updated_at            DATETIME NOT NULL DEFAULT NOW(),
    UNIQUE (plan_id, day_number, style)
);

CREATE TABLE plan_route_map_jobs (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_map_id          BIGINT NOT NULL,
    plan_id               BIGINT NOT NULL,
    user_id               BIGINT NOT NULL,
    day_number            INT NOT NULL,
    style                 VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'queued',
    trigger_type          VARCHAR(64),
    locked_by             VARCHAR(128),
    locked_until          DATETIME,
    attempts              INT NOT NULL DEFAULT 0,
    max_attempts          INT NOT NULL DEFAULT 3,
    error_code            VARCHAR(64),
    error_message         VARCHAR(512),
    available_at          DATETIME NOT NULL DEFAULT NOW(),
    started_at            DATETIME,
    completed_at          DATETIME,
    created_at            DATETIME NOT NULL DEFAULT NOW(),
    updated_at            DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE task_execution_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid    VARCHAR(36) NOT NULL,
    event_type   VARCHAR(32) NOT NULL,
    status       VARCHAR(32),
    step_index   INT,
    total_steps  INT,
    message      VARCHAR(512),
    details_json CLOB,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE task_checkpoint_artifacts (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid      VARCHAR(64) NOT NULL,
    task_id        BIGINT,
    artifact_type  VARCHAR(64) NOT NULL,
    payload_json   TEXT NOT NULL,
    item_count     INT NOT NULL DEFAULT 0,
    schema_version VARCHAR(16) NOT NULL DEFAULT '2.0',
    created_at     DATETIME NOT NULL DEFAULT NOW(),
    updated_at     DATETIME NOT NULL DEFAULT NOW(),
    UNIQUE (task_uuid, artifact_type)
);

CREATE TABLE admin_audit_logs (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_user_id  BIGINT NOT NULL,
    permission     VARCHAR(64) NOT NULL,
    action         VARCHAR(64) NOT NULL,
    target_type    VARCHAR(64),
    target_id      VARCHAR(128),
    request_ip     VARCHAR(64),
    user_agent     VARCHAR(255),
    details_json   CLOB,
    created_at     DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE user_quota_config (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_level           INT NOT NULL UNIQUE,
    daily_token_limit    INT NOT NULL DEFAULT 10000,
    monthly_token_limit  INT NOT NULL DEFAULT 100000,
    max_concurrent_tasks INT NOT NULL DEFAULT 2,
    max_plan_steps       INT NOT NULL DEFAULT 15,
    route_map_daily_limit INT,
    created_at           DATETIME NOT NULL DEFAULT NOW(),
    updated_at           DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE rag_documents (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    oss_key          VARCHAR(512) NOT NULL UNIQUE,
    title            VARCHAR(255),
    region           VARCHAR(128),
    doc_type         VARCHAR(32),
    source_type      VARCHAR(32) NOT NULL DEFAULT 'static_knowledge',
    source_name      VARCHAR(128),
    source_url       VARCHAR(512),
    metadata_json    CLOB,
    status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    ingest_progress  INT NOT NULL DEFAULT 0,
    retry_count      INT NOT NULL DEFAULT 0,
    last_error_code  VARCHAR(64),
    error_message    CLOB,
    disabled_at      DATETIME,
    created_at       DATETIME NOT NULL DEFAULT NOW(),
    updated_at       DATETIME NOT NULL DEFAULT NOW()
);

CREATE TABLE rag_chunks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id      BIGINT NOT NULL,
    chunk_index      INT NOT NULL,
    chunk_text       CLOB NOT NULL,
    dashvector_id    VARCHAR(128),
    embedding_json   CLOB,
    embedding_vector CLOB,
    search_text      CLOB,
    metadata_json    CLOB,
    token_count      INT,
    created_at       DATETIME NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_doc_chunk UNIQUE (document_id, chunk_index)
);
