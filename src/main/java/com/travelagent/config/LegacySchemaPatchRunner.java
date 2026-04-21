package com.travelagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies small, idempotent compatibility patches for older local schemas.
 * This keeps existing developer databases usable after new columns are added.
 */
@Component
public class LegacySchemaPatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaPatchRunner.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public LegacySchemaPatchRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> appliedPatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "users")) {
                log.info("Legacy schema patch skipped: users table does not exist yet");
                return;
            }

            ensureColumn(connection, appliedPatches, "users", "user_level",
                "ALTER TABLE users ADD COLUMN user_level TINYINT NOT NULL DEFAULT 1 COMMENT '1=REGULAR, 2=VIP, 3=ADMIN'");
            ensureColumn(connection, appliedPatches, "users", "status",
                "ALTER TABLE users ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled'");
            ensureColumn(connection, appliedPatches, "users", "created_at",
                "ALTER TABLE users ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
            ensureColumn(connection, appliedPatches, "users", "updated_at",
                "ALTER TABLE users ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
            ensureColumn(connection, appliedPatches, "users", "deleted_at",
                "ALTER TABLE users ADD COLUMN deleted_at DATETIME NULL DEFAULT NULL COMMENT 'NULL means not deleted (soft delete)'");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect database schema for compatibility patches", ex);
        }

        if (appliedPatches.isEmpty()) {
            log.info("Legacy schema patch check complete: no database changes needed");
            return;
        }
        log.warn("Applied legacy schema compatibility patches: {}", appliedPatches);
    }

    private void ensureColumn(Connection connection, List<String> appliedPatches,
                              String tableName, String columnName, String alterSql) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute(alterSql);
        appliedPatches.add(tableName + "." + columnName);
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = resolveSchema(connection);
        try (ResultSet resultSet = metaData.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getTables(catalog, schema, tableName.toUpperCase(Locale.ROOT),
            new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = resolveSchema(connection);
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, columnName)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getColumns(catalog, schema,
            tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))) {
            return resultSet.next();
        }
    }

    private String resolveSchema(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        String productName = connection.getMetaData().getDatabaseProductName();
        if (productName != null && productName.toLowerCase(Locale.ROOT).contains("h2")) {
            return "PUBLIC";
        }
        return null;
    }
}
