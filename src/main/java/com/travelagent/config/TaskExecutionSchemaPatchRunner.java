package com.travelagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

@Component
public class TaskExecutionSchemaPatchRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSchemaGuard schemaGuard;

    public TaskExecutionSchemaPatchRunner(JdbcTemplate jdbcTemplate, DatabaseSchemaGuard schemaGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaGuard = schemaGuard;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (!schemaGuard.tableExists("tasks")) {
            return;
        }
        ensureColumn("revision", "ALTER TABLE tasks ADD COLUMN revision BIGINT NOT NULL DEFAULT 1");
        ensureColumn("lease_token", "ALTER TABLE tasks ADD COLUMN lease_token VARCHAR(64) NULL");
        ensureColumn("lease_expires_at", "ALTER TABLE tasks ADD COLUMN lease_expires_at DATETIME NULL");
        ensureColumn("execution_owner", "ALTER TABLE tasks ADD COLUMN execution_owner VARCHAR(128) NULL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS task_operations (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    task_uuid VARCHAR(36) NOT NULL,
                    operation_id VARCHAR(128) NOT NULL,
                    operation_type VARCHAR(64) NOT NULL,
                    result_status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
                    result_json MEDIUMTEXT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_operation (user_id, task_uuid, operation_id),
                    INDEX idx_task_operation_task (task_uuid, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tool_execution_records (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_uuid VARCHAR(36) NOT NULL,
                    user_id BIGINT NULL,
                    tool_name VARCHAR(128) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    argument_fingerprint CHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
                    result_json MEDIUMTEXT NULL,
                    error_message TEXT NULL,
                    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at DATETIME NULL,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_tool_execution_idempotency (task_uuid, idempotency_key),
                    INDEX idx_tool_execution_status (status, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void ensureColumn(String name, String sql) throws SQLException {
        if (!schemaGuard.columnExists("tasks", name)) {
            jdbcTemplate.execute(sql);
        }
    }
}
