package com.flowops.execution.executors;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.config.ExecutionProperties;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeLogger;
import com.flowops.execution.NodeResult;
import com.flowops.execution.VariableInterpolator;
import com.flowops.workflow.graph.GraphNode;
import com.flowops.workflow.graph.GraphPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

class DatabaseTransactionExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionProperties PROPERTIES = new ExecutionProperties(
            4, 3, Duration.ofSeconds(2), Duration.ofSeconds(30), 300,
            Duration.ofSeconds(5), Duration.ofSeconds(30), 1_048_576, Duration.ofMinutes(30));

    private String h2Url;
    private DatabaseTransactionExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        String dbName = "db_tx_test_" + UUID.randomUUID().toString().replace("-", "");
        h2Url = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE users (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(255), " +
                    "role VARCHAR(50), " +
                    "balance DECIMAL(10,2))");
        }

        executor = new DatabaseTransactionExecutor() {
            @Override
            Connection createConnection(String url, Properties props) throws SQLException {
                return DriverManager.getConnection(h2Url, "sa", "");
            }

            @Override
            void validateTarget(String host, String database) {
                // Bypass network DNS resolution for hermetic in-memory test
            }
        };
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
        }
    }

    private NodeExecutionContext buildContext(Map<String, Object> config, ObjectNode variables) {
        GraphNode node = new GraphNode(
                "node-db-tx",
                "database_transaction",
                new GraphPosition(0, 0),
                Map.of("config", config));

        return new NodeExecutionContext(
                node,
                config,
                Map.of(),
                variables != null ? variables : MAPPER.createObjectNode(),
                new VariableInterpolator(),
                MAPPER,
                PROPERTIES,
                Mockito.mock(NodeLogger.class));
    }

    private Map<String, Object> baseConfig(Object statements) {
        Map<String, Object> config = new HashMap<>();
        config.put("databaseType", "postgresql");
        config.put("host", "db.example.com");
        config.put("port", "5432");
        config.put("database", "appdb");
        config.put("username", "testuser");
        config.put("password", "secret");
        config.put("statements", statements);
        return config;
    }

    @Test
    void normalParameterizedExecution_textStatements() throws SQLException {
        ObjectNode vars = MAPPER.createObjectNode();
        ObjectNode trigger = vars.putObject("trigger");
        trigger.put("id", 1);
        trigger.put("name", "Alice");
        trigger.put("role", "admin");
        trigger.put("balance", 150.50);

        ArrayNode stmts = MAPPER.createArrayNode();
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES ({{trigger.id}}, '{{trigger.name}}', '{{trigger.role}}', {{trigger.balance}})");
        stmts.add("SELECT id, name, role, balance FROM users WHERE id = {{trigger.id}}");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), vars);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        JsonNode output = result.output();
        assertThat(output.get("committed").asBoolean()).isTrue();

        JsonNode results = output.get("results");
        assertThat(results.size()).isEqualTo(2);

        // Statement 1: Insert
        assertThat(results.get(0).get("affectedRows").asInt()).isEqualTo(1);
        assertThat(results.get(0).get("sql").asText()).isEqualTo(
                "INSERT INTO users (id, name, role, balance) VALUES (?, ?, ?, ?)");

        // Statement 2: Select
        assertThat(results.get(1).get("rowCount").asInt()).isEqualTo(1);
        JsonNode row = results.get(1).get("rows").get(0);
        assertThat(row.get("ID").asInt()).isEqualTo(1);
        assertThat(row.get("NAME").asText()).isEqualTo("Alice");
        assertThat(row.get("ROLE").asText()).isEqualTo("admin");
        assertThat(row.get("BALANCE").asDouble()).isEqualTo(150.50);

        // Direct DB verification
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void normalParameterizedExecution_objectStatementsWithExplicitParams() throws SQLException {
        ObjectNode vars = MAPPER.createObjectNode();
        ObjectNode trigger = vars.putObject("trigger");
        trigger.put("id", 2);
        trigger.put("name", "Bob");

        ArrayNode stmts = MAPPER.createArrayNode();

        ObjectNode insertStmt = MAPPER.createObjectNode();
        insertStmt.put("sql", "INSERT INTO users (id, name, role, balance) VALUES (?, ?, ?, ?)");
        ArrayNode insertParams = insertStmt.putArray("params");
        insertParams.add("{{trigger.id}}");
        insertParams.add("{{trigger.name}}");
        insertParams.add("developer");
        insertParams.add(300.0);
        stmts.add(insertStmt);

        ObjectNode selectStmt = MAPPER.createObjectNode();
        selectStmt.put("sql", "SELECT name, role FROM users WHERE id = ?");
        ArrayNode selectParams = selectStmt.putArray("params");
        selectParams.add(2);
        stmts.add(selectStmt);

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), vars);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        JsonNode output = result.output();
        assertThat(output.get("committed").asBoolean()).isTrue();

        JsonNode results = output.get("results");
        assertThat(results.size()).isEqualTo(2);
        assertThat(results.get(0).get("affectedRows").asInt()).isEqualTo(1);

        JsonNode rows = results.get(1).get("rows");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("NAME").asText()).isEqualTo("Bob");
        assertThat(rows.get(0).get("ROLE").asText()).isEqualTo("developer");
    }

    @Test
    void maliciousPayload_or1equals1_treatedAsDataAndCannotAlterSqlStructure() throws SQLException {
        // Seed database with two users
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'admin', 100.0)");
            stmt.execute("INSERT INTO users VALUES (2, 'Bob', 'user', 200.0)");
        }

        // Attacker payload aimed at altering SQL logic: ' OR '1'='1
        ObjectNode vars = MAPPER.createObjectNode();
        vars.putObject("trigger").put("name", "' OR '1'='1");

        ArrayNode stmts = MAPPER.createArrayNode();
        stmts.add("SELECT * FROM users WHERE name = '{{trigger.name}}'");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), vars);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        JsonNode output = result.output();
        JsonNode resultItem = output.get("results").get(0);

        // In vulnerable code: SQL was "WHERE name = '' OR '1'='1'", returning 2 rows!
        // In hardened code: treated strictly as literal data, returning 0 rows!
        assertThat(resultItem.get("rowCount").asInt()).isEqualTo(0);
        assertThat(resultItem.get("rows").size()).isEqualTo(0);
        assertThat(resultItem.get("sql").asText()).isEqualTo("SELECT * FROM users WHERE name = ?");

        // Now insert a row whose name is literally "' OR '1'='1"
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users VALUES (3, ''' OR ''1''=''1', 'guest', 0.0)");
        }

        // Run the exact same query with the same payload
        NodeResult result2 = executor.execute(ctx);
        assertThat(result2.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        JsonNode output2 = result2.output();
        JsonNode resultItem2 = output2.get("results").get(0);

        // Only the row whose name is literally "' OR '1'='1" matches!
        assertThat(resultItem2.get("rowCount").asInt()).isEqualTo(1);
        assertThat(resultItem2.get("rows").get(0).get("ID").asInt()).isEqualTo(3);
    }

    @Test
    void maliciousPayload_sqlCommentPayloads_treatedAsData() throws SQLException {
        // Seed database
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'user', 100.0)");
            stmt.execute("INSERT INTO users VALUES (2, 'AdminUser', 'admin', 500.0)");
        }

        // Attacker attempts to comment out the rest of the WHERE clause: "Alice' --"
        ObjectNode vars = MAPPER.createObjectNode();
        vars.putObject("trigger").put("name", "Alice' --");

        ArrayNode stmts = MAPPER.createArrayNode();
        stmts.add("SELECT * FROM users WHERE name = '{{trigger.name}}' AND role = 'admin'");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), vars);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        JsonNode results = result.output().get("results");

        // If injection succeeded, SQL comment '--' would comment out "AND role = 'admin'"
        // and return Alice (id=1) as admin!
        // With parameterization, '--' is part of the data string, matching 0 rows.
        assertThat(results.get(0).get("rowCount").asInt()).isEqualTo(0);

        // Test block comment payload: "Alice' /*"
        vars.putObject("trigger").put("name", "Alice' /*");
        NodeResult resultBlockComment = executor.execute(ctx);
        assertThat(resultBlockComment.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        assertThat(resultBlockComment.output().get("results").get(0).get("rowCount").asInt()).isEqualTo(0);
    }

    @Test
    void maliciousPayload_quotesAndSpecialCharacters() throws SQLException {
        // Attacker payload with quotes, semicolons, and stacked SQL commands
        String maliciousName = "O'Connor; DROP TABLE users; --";
        ObjectNode vars = MAPPER.createObjectNode();
        vars.putObject("trigger").put("name", maliciousName);

        ArrayNode stmts = MAPPER.createArrayNode();
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES (10, '{{trigger.name}}', 'user', 50.0)");
        stmts.add("SELECT name FROM users WHERE id = 10");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), vars);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        assertThat(result.output().get("committed").asBoolean()).isTrue();

        JsonNode results = result.output().get("results");
        assertThat(results.get(0).get("affectedRows").asInt()).isEqualTo(1);

        // Verify the users table still exists and data was stored verbatim
        JsonNode rows = results.get(1).get("rows");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("NAME").asText()).isEqualTo(maliciousName);

        // Direct DB verification: table still intact
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 10")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(maliciousName);
        }
    }

    @Test
    void transactionRollbackBehavior_whenExecutionFails() throws SQLException {
        // Verify initially empty
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }

        ArrayNode stmts = MAPPER.createArrayNode();
        // Statement 1: Valid insert
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES (1, 'Alice', 'user', 100.0)");
        // Statement 2: Fails with duplicate primary key constraint violation
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES (1, 'DuplicateKey', 'user', 200.0)");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), null);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(result.error()).contains("Transaction rolled back");

        // Verify that statement 1 was rolled back and NOT persisted
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    @Test
    void transactionCommitBehavior_multipleStatementsAllSucceed() throws SQLException {
        ArrayNode stmts = MAPPER.createArrayNode();
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES (1, 'Alice', 'user', 200.0)");
        stmts.add("INSERT INTO users (id, name, role, balance) VALUES (2, 'Bob', 'user', 100.0)");
        stmts.add("UPDATE users SET balance = balance - 50.0 WHERE id = 1");
        stmts.add("UPDATE users SET balance = balance + 50.0 WHERE id = 2");

        NodeExecutionContext ctx = buildContext(baseConfig(stmts), null);
        NodeResult result = executor.execute(ctx);

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.SUCCESS);
        assertThat(result.output().get("committed").asBoolean()).isTrue();

        // Verify in database: Alice has 150, Bob has 150
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs1 = stmt.executeQuery("SELECT balance FROM users WHERE id = 1")) {
                assertThat(rs1.next()).isTrue();
                assertThat(rs1.getDouble(1)).isEqualTo(150.0);
            }
            try (ResultSet rs2 = stmt.executeQuery("SELECT balance FROM users WHERE id = 2")) {
                assertThat(rs2.next()).isTrue();
                assertThat(rs2.getDouble(1)).isEqualTo(150.0);
            }
        }
    }

    @Test
    void validation_missingOrInvalidConfigurations() {
        DatabaseTransactionExecutor defaultExec = new DatabaseTransactionExecutor();

        // Missing databaseType
        Map<String, Object> cfg1 = baseConfig(MAPPER.createArrayNode());
        cfg1.remove("databaseType");
        assertThat(defaultExec.execute(buildContext(cfg1, null)).error())
                .contains("requires 'databaseType'");

        // Missing host
        Map<String, Object> cfg2 = baseConfig(MAPPER.createArrayNode());
        cfg2.remove("host");
        assertThat(defaultExec.execute(buildContext(cfg2, null)).error())
                .contains("requires 'host'");

        // Missing database
        Map<String, Object> cfg3 = baseConfig(MAPPER.createArrayNode());
        cfg3.remove("database");
        assertThat(defaultExec.execute(buildContext(cfg3, null)).error())
                .contains("requires 'database'");

        // Missing username
        Map<String, Object> cfg4 = baseConfig(MAPPER.createArrayNode());
        cfg4.remove("username");
        assertThat(defaultExec.execute(buildContext(cfg4, null)).error())
                .contains("requires 'username'");

        // Missing statements
        Map<String, Object> cfg5 = baseConfig(null);
        cfg5.remove("statements");
        assertThat(defaultExec.execute(buildContext(cfg5, null)).error())
                .contains("requires 'statements' array");

        // SSRF blocked host
        Map<String, Object> cfg6 = baseConfig(MAPPER.createArrayNode());
        cfg6.put("host", "127.0.0.1");
        assertThat(defaultExec.execute(buildContext(cfg6, null)).error())
                .contains("Database target rejected");
    }
}
