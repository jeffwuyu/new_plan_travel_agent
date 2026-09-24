package com.travelagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies small, idempotent compatibility patches for older local schemas.
 * This keeps existing developer databases usable after new columns are added.
 */
@Component
public class LegacySchemaPatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaPatchRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSchemaGuard schemaGuard;

    /**
     * 初始化LegacySchemaPatchRunner 实例。
     * @param jdbcTemplate j db cT em pl at e 参数
     * @param schemaGuard s ch em aG ua rd 参数
     */
    public LegacySchemaPatchRunner(JdbcTemplate jdbcTemplate, DatabaseSchemaGuard schemaGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaGuard = schemaGuard;
    }

    /**
     * 处理run。
     * @param args a rg s 参数
     */
    @Override
    public void run(ApplicationArguments args) {
        List<String> appliedPatches = new ArrayList<>();
        try {
            if (!schemaGuard.tableExists("users")) {
                log.info("Legacy schema patch skipped: users table does not exist yet");
            } else {
                ensureColumn(appliedPatches, "users", "user_level",
                    "ALTER TABLE users ADD COLUMN user_level TINYINT NOT NULL DEFAULT 1 COMMENT '1=REGULAR, 2=VIP, 3=ADMIN'");
                ensureColumn(appliedPatches, "users", "status",
                    "ALTER TABLE users ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled'");
                ensureColumn(appliedPatches, "users", "created_at",
                    "ALTER TABLE users ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
                ensureColumn(appliedPatches, "users", "updated_at",
                    "ALTER TABLE users ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
                ensureColumn(appliedPatches, "users", "deleted_at",
                    "ALTER TABLE users ADD COLUMN deleted_at DATETIME NULL DEFAULT NULL COMMENT 'NULL means not deleted (soft delete)'");
            }

            if (!schemaGuard.tableExists("user_memory_profile")) {
                jdbcTemplate.execute("""
                    CREATE TABLE user_memory_profile (
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable low-priority long-term user memory profile'
                    """);
                appliedPatches.add("user_memory_profile");
            }

            if (!schemaGuard.tableExists("user_memory_fact")) {
                jdbcTemplate.execute("""
                    CREATE TABLE user_memory_fact (
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable user memory facts with confidence and evidence'
                    """);
                appliedPatches.add("user_memory_fact");
            }

            if (!schemaGuard.tableExists("plans")) {
                log.info("Legacy schema patch skipped: plans table does not exist yet");
            } else {
                ensureColumn(appliedPatches, "plans", "start_location_query",
                    "ALTER TABLE plans ADD COLUMN start_location_query VARCHAR(128) NULL");
                ensureColumn(appliedPatches, "plans", "end_location_query",
                    "ALTER TABLE plans ADD COLUMN end_location_query VARCHAR(128) NULL");
                ensureColumn(appliedPatches, "plans", "trip_start_time",
                    "ALTER TABLE plans ADD COLUMN trip_start_time DATETIME NULL");
                ensureColumn(appliedPatches, "plans", "trip_end_time",
                    "ALTER TABLE plans ADD COLUMN trip_end_time DATETIME NULL");
                ensureColumn(appliedPatches, "plans", "full_day_start_time",
                    "ALTER TABLE plans ADD COLUMN full_day_start_time TIME NULL");
                ensureColumn(appliedPatches, "plans", "full_day_end_time",
                    "ALTER TABLE plans ADD COLUMN full_day_end_time TIME NULL");
                ensureColumn(appliedPatches, "plans", "destination_buffer_min",
                    "ALTER TABLE plans ADD COLUMN destination_buffer_min INT NULL");
                ensureColumn(appliedPatches, "plans", "accommodation_status",
                    "ALTER TABLE plans ADD COLUMN accommodation_status VARCHAR(32) NULL");
                ensureColumn(appliedPatches, "plans", "accommodation_failure_reason",
                    "ALTER TABLE plans ADD COLUMN accommodation_failure_reason VARCHAR(512) NULL");
            }

            if (!schemaGuard.tableExists("tasks")) {
                log.info("Legacy schema patch skipped: tasks table does not exist yet");
            } else {
                ensureColumn(appliedPatches, "tasks", "request_ip",
                    "ALTER TABLE tasks ADD COLUMN request_ip VARCHAR(45) NULL COMMENT 'Client IP captured when the task was created'");
            }

            if (!schemaGuard.tableExists("user_quota_config")) {
                log.info("Legacy schema patch skipped: user_quota_config table does not exist yet");
            } else {
                ensureColumn(appliedPatches, "user_quota_config", "route_map_daily_limit",
                    "ALTER TABLE user_quota_config ADD COLUMN route_map_daily_limit INT NULL COMMENT 'Daily new route map record limit'");
            }

            if (!schemaGuard.tableExists("plan_accommodations")) {
                jdbcTemplate.execute("""
                    CREATE TABLE plan_accommodations (
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA accommodation recommendations for plan nights'
                    """);
                appliedPatches.add("plan_accommodations");
            }

            if (!schemaGuard.tableExists("plan_steps")) {
                log.info("Legacy schema patch skipped: plan_steps table does not exist yet");
            } else {
                ensureColumn(appliedPatches, "plan_steps", "planned_start_time",
                    "ALTER TABLE plan_steps ADD COLUMN planned_start_time DATETIME NULL");
                ensureColumn(appliedPatches, "plan_steps", "planned_end_time",
                    "ALTER TABLE plan_steps ADD COLUMN planned_end_time DATETIME NULL");
                ensureColumn(appliedPatches, "plan_steps", "travel_time_to_destination_min",
                    "ALTER TABLE plan_steps ADD COLUMN travel_time_to_destination_min INT NULL");
                ensureColumn(appliedPatches, "plan_steps", "traffic_mode_from_prev",
                    "ALTER TABLE plan_steps ADD COLUMN traffic_mode_from_prev VARCHAR(32) NULL COMMENT 'Agent selected travel mode from previous step'");
                ensureColumn(appliedPatches, "plan_steps", "selected_route_summary_from_prev",
                    "ALTER TABLE plan_steps ADD COLUMN selected_route_summary_from_prev VARCHAR(512) NULL COMMENT 'Agent selected route summary from previous step'");
                ensureColumn(appliedPatches, "plan_steps", "selected_route_geometry_json",
                    "ALTER TABLE plan_steps ADD COLUMN selected_route_geometry_json MEDIUMTEXT NULL COMMENT 'Agent selected route geometry from previous step'");
            }

            if (!schemaGuard.tableExists("plan_day_route_maps")) {
                jdbcTemplate.execute("""
                    CREATE TABLE plan_day_route_maps (
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily AI route maps for travel plans'
                    """);
                appliedPatches.add("plan_day_route_maps");
            }

            if (!schemaGuard.tableExists("plan_route_map_jobs")) {
                jdbcTemplate.execute("""
                    CREATE TABLE plan_route_map_jobs (
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent route-map generation worker jobs and dead-letter records'
                    """);
                appliedPatches.add("plan_route_map_jobs");
            }

            if (!schemaGuard.tableExists("rag_documents")) {
                jdbcTemplate.execute("""
                    CREATE TABLE rag_documents (
                        id               BIGINT       NOT NULL AUTO_INCREMENT,
                        oss_key          VARCHAR(512) NOT NULL,
                        title            VARCHAR(255) NULL,
                        region           VARCHAR(128) NULL,
                        doc_type         VARCHAR(32)  NULL,
                        source_type      VARCHAR(32)  NOT NULL DEFAULT 'static_knowledge',
                        source_name      VARCHAR(128) NULL,
                        source_url       VARCHAR(512) NULL,
                        metadata_json    TEXT         NULL,
                        status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
                        ingest_progress  INT          NOT NULL DEFAULT 0,
                        retry_count      INT          NOT NULL DEFAULT 0,
                        last_error_code  VARCHAR(64)  NULL,
                        error_message    TEXT         NULL,
                        disabled_at      DATETIME     NULL,
                        created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_oss_key (oss_key),
                        INDEX idx_status (status),
                        INDEX idx_region (region),
                        INDEX idx_source_type (source_type)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG source documents stored in OSS'
                    """);
                appliedPatches.add("rag_documents");
            } else {
                ensureColumn(appliedPatches, "rag_documents", "ingest_progress",
                    "ALTER TABLE rag_documents ADD COLUMN ingest_progress INT NOT NULL DEFAULT 0");
                ensureColumn(appliedPatches, "rag_documents", "retry_count",
                    "ALTER TABLE rag_documents ADD COLUMN retry_count INT NOT NULL DEFAULT 0");
                ensureColumn(appliedPatches, "rag_documents", "last_error_code",
                    "ALTER TABLE rag_documents ADD COLUMN last_error_code VARCHAR(64) NULL");
                ensureColumn(appliedPatches, "rag_documents", "disabled_at",
                    "ALTER TABLE rag_documents ADD COLUMN disabled_at DATETIME NULL");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect database schema for compatibility patches", ex);
        }

        if (appliedPatches.isEmpty()) {
            log.info("Legacy schema patch check complete: no database changes needed");
        } else {
            log.warn("Applied legacy schema compatibility patches: {}", appliedPatches);
        }

        if (!schemaGuard.refreshCoreSchemaReady()) {
            log.warn("Database schema still incomplete after startup patch. Missing tables: {}",
                schemaGuard.getLastMissingTables());
        }
    }

    /**
     * 处理ensureColumn。
     * @param appliedPatches a pp li ed Pa tc he s 参数
     * @param tableName t ab le Na me 参数
     * @param columnName c ol um nN am e 参数
     * @param alterSql a lt er Sq l 参数
     */
    private void ensureColumn(List<String> appliedPatches, String tableName,
                              String columnName, String alterSql) throws SQLException {
        if (schemaGuard.columnExists(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute(alterSql);
        appliedPatches.add(tableName + "." + columnName);
    }
}
