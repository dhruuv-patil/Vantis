package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Database Transaction: runs a list of statements in one transaction. */
@Component
public class DatabaseTransactionExecutor implements NodeExecutor {

    private static final Pattern EXACT_TOKEN = Pattern.compile("^\\{\\{\\s*([^}]+?)\\s*}}$");

    @Override
    public String type() {
        return "database_transaction";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String dbType = ctx.configString("databaseType");
        String host = ctx.configString("host");
        String portStr = ctx.configString("port");
        String database = ctx.configString("database");
        String username = ctx.configString("username");
        String password = ctx.configString("password");
        Object rawStatements = ctx.config().get("statements");
        JsonNode statementsNode = null;
        if (rawStatements instanceof JsonNode jn) {
            statementsNode = jn;
        } else if (rawStatements instanceof String s && !s.isBlank()) {
            try {
                statementsNode = ctx.mapper().readTree(s);
            } catch (Exception ignored) {
            }
        } else if (rawStatements instanceof List<?> list) {
            statementsNode = ctx.mapper().valueToTree(list);
        }

        if (dbType == null || dbType.isBlank())
            return NodeResult.fail("Database Transaction requires 'databaseType'.");
        if (host == null || host.isBlank())
            return NodeResult.fail("Database Transaction requires 'host'.");
        if (database == null || database.isBlank())
            return NodeResult.fail("Database Transaction requires 'database'.");
        if (username == null || username.isBlank())
            return NodeResult.fail("Database Transaction requires 'username'.");
        if (statementsNode == null || !statementsNode.isArray())
            return NodeResult.fail("Database Transaction requires 'statements' array.");

        int port = defaultPort(dbType);
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            validateTarget(host, database);
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

        try (Connection conn = createConnection(url, props)) {
            conn.setAutoCommit(false);
            try {
                ArrayNode results = ctx.mapper().createArrayNode();
                for (JsonNode stmtNode : statementsNode) {
                    StatementSpec spec = parseStatement(stmtNode, ctx);
                    try (PreparedStatement stmt = conn.prepareStatement(spec.sql)) {
                        bindParameters(stmt, spec.params);
                        boolean hasResults = stmt.execute();
                        ObjectNode result = ctx.mapper().createObjectNode();
                        result.put("sql", spec.sql);
                        if (hasResults) {
                            try (ResultSet rs = stmt.getResultSet()) {
                                ArrayNode rows = ctx.mapper().createArrayNode();
                                ResultSetMetaData meta = rs.getMetaData();
                                int colCount = meta.getColumnCount();
                                while (rs.next()) {
                                    ObjectNode row = ctx.mapper().createObjectNode();
                                    for (int i = 1; i <= colCount; i++) {
                                        String colName = meta.getColumnLabel(i);
                                        Object val = rs.getObject(i);
                                        row.set(colName, toJsonNode(ctx, val));
                                    }
                                    rows.add(row);
                                }
                                result.set("rows", rows);
                                result.put("rowCount", rows.size());
                            }
                        } else {
                            result.put("affectedRows", stmt.getUpdateCount());
                        }
                        results.add(result);
                    }
                }
                conn.commit();
                ObjectNode output = ctx.mapper().createObjectNode();
                output.set("results", results);
                output.put("committed", true);
                ctx.log().info(
                        "Database Transaction (" + dbType + ") committed " + statementsNode.size() + " statements.");
                return NodeResult.success(output);
            } catch (Exception e) {
                conn.rollback();
                ctx.log().error("Database Transaction rolled back: " + e.getMessage());
                return NodeResult.fail("Transaction rolled back: " + e.getMessage());
            }
        } catch (SQLException e) {
            return NodeResult.fail(dbType + " error: " + e.getMessage());
        } catch (Exception e) {
            return NodeResult.fail(dbType + " driver not available: " + e.getMessage());
        }
    }

    Connection createConnection(String url, Properties props) throws SQLException {
        return DriverManager.getConnection(url, props);
    }

    void validateTarget(String host, String database) {
        com.flowops.common.security.SsrfGuard.validateHost(host);
        com.flowops.common.security.SsrfGuard.validateDatabaseName(database);
    }

    static class StatementSpec {
        final String sql;
        final List<Object> params;

        StatementSpec(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params != null ? params : List.of();
        }
    }

    StatementSpec parseStatement(JsonNode stmtNode, NodeExecutionContext ctx) {
        if (stmtNode == null || stmtNode.isNull()) {
            return new StatementSpec("", List.of());
        }

        if (stmtNode.isObject()) {
            String sql = null;
            if (stmtNode.has("sql")) {
                sql = stmtNode.get("sql").asText();
            } else if (stmtNode.has("query")) {
                sql = stmtNode.get("query").asText();
            } else if (stmtNode.has("statement")) {
                sql = stmtNode.get("statement").asText();
            }

            JsonNode paramsNode = null;
            if (stmtNode.has("params")) {
                paramsNode = stmtNode.get("params");
            } else if (stmtNode.has("parameters")) {
                paramsNode = stmtNode.get("parameters");
            } else if (stmtNode.has("values")) {
                paramsNode = stmtNode.get("values");
            } else if (stmtNode.has("args")) {
                paramsNode = stmtNode.get("args");
            }

            if (sql == null) {
                sql = "";
            }

            if (paramsNode != null && paramsNode.isArray()) {
                List<Object> params = new ArrayList<>();
                for (JsonNode paramNode : paramsNode) {
                    params.add(resolveParamValue(paramNode, ctx));
                }
                return new StatementSpec(sql, params);
            }

            return parseTemplateSql(sql, ctx);
        }

        return parseTemplateSql(stmtNode.asText(), ctx);
    }

    private Object resolveParamValue(JsonNode paramNode, NodeExecutionContext ctx) {
        if (paramNode == null || paramNode.isNull() || paramNode.isMissingNode()) {
            return null;
        }
        if (paramNode.isNumber()) {
            return paramNode.numberValue();
        }
        if (paramNode.isBoolean()) {
            return paramNode.asBoolean();
        }
        if (paramNode.isTextual()) {
            String text = paramNode.asText();
            Matcher singleTokenMatcher = EXACT_TOKEN.matcher(text.trim());
            if (singleTokenMatcher.matches()) {
                String path = singleTokenMatcher.group(1);
                JsonNode resolved = ctx.resolve(path);
                return jsonNodeToJavaValue(resolved);
            }
            if (text.contains("{{")) {
                return ctx.interpolate(text);
            }
            return text;
        }
        return paramNode.asText();
    }

    private Object jsonNodeToJavaValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.asText();
    }

    StatementSpec parseTemplateSql(String sql, NodeExecutionContext ctx) {
        if (sql == null || sql.isBlank()) {
            return new StatementSpec(sql == null ? "" : sql, List.of());
        }

        StringBuilder outSql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);

            // 1. Line comment: --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int lineEnd = sql.indexOf('\n', i + 2);
                if (lineEnd == -1) {
                    outSql.append(sql.substring(i));
                    break;
                } else {
                    outSql.append(sql.substring(i, lineEnd + 1));
                    i = lineEnd + 1;
                    continue;
                }
            }

            // 2. Block comment: /* ... */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int commentEnd = sql.indexOf("*/", i + 2);
                if (commentEnd == -1) {
                    outSql.append(sql.substring(i));
                    break;
                } else {
                    outSql.append(sql.substring(i, commentEnd + 2));
                    i = commentEnd + 2;
                    continue;
                }
            }

            // 3. Single-quoted literal: '...'
            if (c == '\'') {
                int start = i;
                i++; // skip opening quote
                StringBuilder literalContent = new StringBuilder();
                boolean closed = false;

                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '\'') {
                        // Check for escaped quote: ''
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            literalContent.append('\'');
                            i += 2;
                        } else {
                            closed = true;
                            i++;
                            break;
                        }
                    } else if (ch == '\\' && i + 1 < len) {
                        literalContent.append(ch);
                        literalContent.append(sql.charAt(i + 1));
                        i += 2;
                    } else {
                        literalContent.append(ch);
                        i++;
                    }
                }

                String literalStr = literalContent.toString();
                if (literalStr.contains("{{")) {
                    outSql.append('?');
                    Matcher matcher = EXACT_TOKEN.matcher(literalStr.trim());
                    if (matcher.matches()) {
                        String path = matcher.group(1);
                        JsonNode resolved = ctx.resolve(path);
                        params.add(jsonNodeToJavaValue(resolved));
                    } else {
                        params.add(ctx.interpolate(literalStr));
                    }
                } else {
                    if (closed) {
                        outSql.append(sql, start, i);
                    } else {
                        outSql.append(sql.substring(start));
                    }
                }
                continue;
            }

            // 4. Double-quoted literal/identifier: "..."
            if (c == '"') {
                int start = i;
                i++; // skip opening quote
                StringBuilder literalContent = new StringBuilder();
                boolean closed = false;

                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            literalContent.append('"');
                            i += 2;
                        } else {
                            closed = true;
                            i++;
                            break;
                        }
                    } else if (ch == '\\' && i + 1 < len) {
                        literalContent.append(ch);
                        literalContent.append(sql.charAt(i + 1));
                        i += 2;
                    } else {
                        literalContent.append(ch);
                        i++;
                    }
                }

                String literalStr = literalContent.toString();
                if (literalStr.contains("{{")) {
                    outSql.append('?');
                    Matcher matcher = EXACT_TOKEN.matcher(literalStr.trim());
                    if (matcher.matches()) {
                        String path = matcher.group(1);
                        JsonNode resolved = ctx.resolve(path);
                        params.add(jsonNodeToJavaValue(resolved));
                    } else {
                        params.add(ctx.interpolate(literalStr));
                    }
                } else {
                    if (closed) {
                        outSql.append(sql, start, i);
                    } else {
                        outSql.append(sql.substring(start));
                    }
                }
                continue;
            }

            // 5. Unquoted template variable: {{ ... }}
            if (c == '{' && i + 1 < len && sql.charAt(i + 1) == '{') {
                int endToken = sql.indexOf("}}", i + 2);
                if (endToken != -1) {
                    String path = sql.substring(i + 2, endToken).trim();
                    outSql.append('?');
                    JsonNode resolved = ctx.resolve(path);
                    params.add(jsonNodeToJavaValue(resolved));
                    i = endToken + 2;
                    continue;
                }
            }

            // 6. Normal character
            outSql.append(c);
            i++;
        }

        return new StatementSpec(outSql.toString(), params);
    }

    private void bindParameters(PreparedStatement stmt, List<Object> params) throws SQLException {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            int index = i + 1;
            if (param == null) {
                stmt.setNull(index, Types.NULL);
            } else if (param instanceof Integer n) {
                stmt.setInt(index, n);
            } else if (param instanceof Long n) {
                stmt.setLong(index, n);
            } else if (param instanceof Double n) {
                stmt.setDouble(index, n);
            } else if (param instanceof Float n) {
                stmt.setFloat(index, n);
            } else if (param instanceof Boolean b) {
                stmt.setBoolean(index, b);
            } else if (param instanceof String s) {
                stmt.setString(index, s);
            } else if (param instanceof Short s) {
                stmt.setShort(index, s);
            } else if (param instanceof Byte b) {
                stmt.setByte(index, b);
            } else if (param instanceof BigDecimal bd) {
                stmt.setBigDecimal(index, bd);
            } else if (param instanceof java.sql.Date d) {
                stmt.setDate(index, d);
            } else if (param instanceof java.sql.Timestamp ts) {
                stmt.setTimestamp(index, ts);
            } else if (param instanceof java.sql.Time t) {
                stmt.setTime(index, t);
            } else {
                stmt.setObject(index, param);
            }
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