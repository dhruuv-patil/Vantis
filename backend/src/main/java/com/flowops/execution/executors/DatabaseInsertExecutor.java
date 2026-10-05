package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.Properties;

/** Database Insert: runs an INSERT against PostgreSQL or MySQL. */
@Component
public class DatabaseInsertExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "database_insert";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String dbType = ctx.configString("databaseType");
        String host = ctx.configString("host");
        String portStr = ctx.configString("port");
        String database = ctx.configString("database");
        String username = ctx.configString("username");
        String password = ctx.configString("password");
        String sql = ctx.configString("sql");

        if (dbType == null || dbType.isBlank())
            return NodeResult.fail("Database Insert requires 'databaseType'.");
        if (host == null || host.isBlank())
            return NodeResult.fail("Database Insert requires 'host'.");
        if (database == null || database.isBlank())
            return NodeResult.fail("Database Insert requires 'database'.");
        if (username == null || username.isBlank())
            return NodeResult.fail("Database Insert requires 'username'.");
        if (sql == null || sql.isBlank())
            return NodeResult.fail("Database Insert requires 'sql'.");

        int port = defaultPort(dbType);
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String interpolatedSql = ctx.interpolate(sql);
        try {
            com.flowops.common.security.SsrfGuard.validateHost(host);
            com.flowops.common.security.SsrfGuard.validateDatabaseName(database);
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail("Database target rejected: " + blocked.getMessage());
        }
        String url = buildUrl(dbType, host, port, database);

        Properties props = new Properties();
        props.setProperty("user", username);
        if (password != null)
            props.setProperty("password", password);
        if ("postgresql".equalsIgnoreCase(dbType))
            props.setProperty("ssl", "false");
        if ("mysql".equalsIgnoreCase(dbType))
            props.setProperty("useSSL", "false");

        try (Connection conn = DriverManager.getConnection(url, props)) {
            try (Statement stmt = conn.createStatement()) {
                int affected = stmt.executeUpdate(interpolatedSql, Statement.RETURN_GENERATED_KEYS);
                ObjectNode output = ctx.mapper().createObjectNode();
                output.put("affectedRows", affected);
                // Try to get generated keys
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        output.put("generatedKey", keys.getObject(1) != null ? keys.getObject(1).toString() : "");
                    }
                }
                ctx.log().info("Database Insert (" + dbType + ") affected " + affected + " rows.");
                return NodeResult.success(output);
            }
        } catch (SQLException e) {
            return NodeResult.fail(dbType + " error: " + e.getMessage());
        } catch (Exception e) {
            return NodeResult.fail(dbType + " driver not available: " + e.getMessage());
        }
    }

    private int defaultPort(String dbType) {
        return "postgresql".equalsIgnoreCase(dbType) ? 5432 : 3306;
    }

    private String buildUrl(String dbType, String host, int port, String database) {
        if ("postgresql".equalsIgnoreCase(dbType)) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        } else {
            return "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true";
        }
    }
}