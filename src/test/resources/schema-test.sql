DROP TABLE IF EXISTS task_execution_events;
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
    created_at             DATETIME NOT NULL DEFAULT NOW()
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
