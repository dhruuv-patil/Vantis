package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.Properties;

/** Database Update: runs an UPDATE against PostgreSQL or MySQL. */
@Component
public class DatabaseUpdateExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "database_update";
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
            return NodeResult.fail("Database Update requires 'databaseType'.");
        if (host == null || host.isBlank())
            return NodeResult.fail("Database Update requires 'host'.");
        if (database == null || database.isBlank())
            return NodeResult.fail("Database Update requires 'database'.");
        if (username == null || username.isBlank())
            return NodeResult.fail("Database Update requires 'username'.");
        if (sql == null || sql.isBlank())
            return NodeResult.fail("Database Update requires 'sql'.");

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
                int affected = stmt.executeUpdate(interpolatedSql);
                ObjectNode output = ctx.mapper().createObjectNode();
                output.put("affectedRows", affected);
                ctx.log().info("Database Update (" + dbType + ") affected " + affected + " rows.");
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