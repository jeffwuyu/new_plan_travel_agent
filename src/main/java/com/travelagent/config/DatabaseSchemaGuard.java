package com.travelagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks whether the runtime database schema is ready for background services.
 * Once all core tables exist, the result is cached for the remainder of the process.
 */
@Component
public class DatabaseSchemaGuard {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaGuard.class);

    private static final List<String> CORE_TABLES = List.of(
        "users",
        "tasks",
        "plans",
        "plan_steps",
        "user_quota_config",
        "task_execution_events"
    );

    private final DataSource dataSource;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private volatile List<String> lastMissingTables = List.of();

    public DatabaseSchemaGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isCoreSchemaReady() {
        if (schemaReady.get()) {
            return true;
        }
        return refreshCoreSchemaReady();
    }

    public synchronized boolean refreshCoreSchemaReady() {
        try (Connection connection = dataSource.getConnection()) {
            List<String> missingTables = findMissingTables(connection, CORE_TABLES);
            if (missingTables.isEmpty()) {
                boolean wasReady = schemaReady.getAndSet(true);
                lastMissingTables = List.of();
                if (!wasReady) {
                    log.info("Database schema ready: core tables verified");
                }
                return true;
            }

            schemaReady.set(false);
            if (!missingTables.equals(lastMissingTables)) {
                log.warn("Database schema not ready: missing tables {}", missingTables);
                lastMissingTables = List.copyOf(missingTables);
            }
            return false;
        } catch (SQLException ex) {
            schemaReady.set(false);
            log.error("Failed to inspect database schema readiness", ex);
            return false;
        }
    }

    public List<String> getLastMissingTables() {
        return lastMissingTables;
    }

    public boolean tableExists(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return tableExists(connection, tableName);
        }
    }

    public boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return columnExists(connection, tableName, columnName);
        }
    }

    private List<String> findMissingTables(Connection connection, List<String> tableNames) throws SQLException {
        List<String> missingTables = new ArrayList<>();
        for (String tableName : tableNames) {
            if (!tableExists(connection, tableName)) {
                missingTables.add(tableName);
            }
        }
        return missingTables;
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
