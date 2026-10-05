package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.Properties;

/** Database Query: runs a read (SELECT) against PostgreSQL or MySQL. */
@Component
public class DatabaseQueryExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "database_query";
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
        String limitStr = ctx.configString("limit");

        if (dbType == null || dbType.isBlank())
            return NodeResult.fail("Database Query requires 'databaseType'.");
        if (host == null || host.isBlank())
            return NodeResult.fail("Database Query requires 'host'.");
        if (database == null || database.isBlank())
            return NodeResult.fail("Database Query requires 'database'.");
        if (username == null || username.isBlank())
            return NodeResult.fail("Database Query requires 'username'.");
        if (sql == null || sql.isBlank())
            return NodeResult.fail("Database Query requires 'sql'.");

        int port = defaultPort(dbType);
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
        }

        int limit = 100;
        if (limitStr != null && !limitStr.isBlank()) {
            try {
                limit = Integer.parseInt(limitStr);
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
            try (PreparedStatement stmt = conn.prepareStatement(interpolatedSql);
                    ResultSet rs = stmt.executeQuery()) {
                ArrayNode rows = ctx.mapper().createArrayNode();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                int count = 0;
                while (rs.next() && count < limit) {
                    ObjectNode row = ctx.mapper().createObjectNode();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = meta.getColumnLabel(i);
                        Object val = rs.getObject(i);
                        row.set(colName, toJsonNode(ctx, val));
                    }
                    rows.add(row);
                    count++;
                }
                ObjectNode output = ctx.mapper().createObjectNode();
                output.set("rows", rows);
                output.put("count", count);
                ctx.log().info("Database Query (" + dbType + ") returned " + count + " rows.");
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

    private JsonNode toJsonNode(NodeExecutionContext ctx, Object val) {
        if (val == null)
            return ctx.mapper().nullNode();
        if (val instanceof Number)
            return ctx.mapper().valueToTree(val);
        if (val instanceof Boolean)
            return ctx.mapper().valueToTree(val);
        if (val instanceof java.sql.Timestamp)
            return ctx.mapper().getNodeFactory().textNode(val.toString());
        if (val instanceof java.sql.Date)
            return ctx.mapper().getNodeFactory().textNode(val.toString());
        if (val instanceof java.sql.Time)
            return ctx.mapper().getNodeFactory().textNode(val.toString());
        return ctx.mapper().getNodeFactory().textNode(val.toString());
    }
}