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

    public LegacySchemaPatchRunner(JdbcTemplate jdbcTemplate, DatabaseSchemaGuard schemaGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaGuard = schemaGuard;
    }

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

    private void ensureColumn(List<String> appliedPatches, String tableName,
                              String columnName, String alterSql) throws SQLException {
        if (schemaGuard.columnExists(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute(alterSql);
        appliedPatches.add(tableName + "." + columnName);
    }
}
