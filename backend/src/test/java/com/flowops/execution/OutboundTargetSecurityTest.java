package com.flowops.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.common.error.ApiException;
import com.flowops.config.ExecutionProperties;
import com.flowops.execution.executors.ApiHealthCheckExecutor;
import com.flowops.execution.executors.ApiPollExecutor;
import com.flowops.execution.executors.DatabaseQueryExecutor;
import com.flowops.execution.executors.DownloadFileExecutor;
import com.flowops.execution.executors.GitHubExecutor;
import com.flowops.execution.executors.GraphQLRequestExecutor;
import com.flowops.execution.executors.HttpRequestExecutor;
import com.flowops.execution.executors.HttpRetryExecutor;
import com.flowops.execution.executors.JiraExecutor;
import com.flowops.execution.executors.MongoDbExecutor;
import com.flowops.execution.executors.PaginatedApiRequestExecutor;
import com.flowops.execution.executors.DiscordExecutor;
import com.flowops.execution.executors.PostgreSqlExecutor;
import com.flowops.execution.executors.RateLimitedRequestExecutor;
import com.flowops.execution.executors.RedisGetExecutor;
import com.flowops.execution.executors.SlackExecutor;
import com.flowops.execution.executors.UploadFileExecutor;
import com.flowops.execution.executors.WaitForHttpExecutor;
import com.flowops.execution.executors.XmlParseExecutor;
import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.smtp.SmtpProvider;
import com.flowops.integration.delivery.smtp.SmtpTransport;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.workflow.graph.GraphNode;
import com.flowops.workflow.graph.GraphPosition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression tests for audit findings F-01, F-02, F-05, F-06: every node that opens a
 * socket to a user-chosen target must refuse loopback / metadata / internal targets,
 * and a stored credential must never be sent to a node-supplied host.
 * All targets are IP literals or service names, so nothing here touches the network.
 */
class OutboundTargetSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExecutionProperties PROPS = new ExecutionProperties(
            4,
            3,
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            300,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            1_048_576,
            Duration.ofMinutes(30));

    private static final String METADATA =
            "http://169.254.169.254/latest/meta-data/";

    private NodeExecutionContext ctx(
            String type,
            Map<String, Object> config,
            Map<String, String> secrets) {

        GraphNode node = new GraphNode(
                "n1",
                type,
                new GraphPosition(0, 0),
                Map.of("config", config));

        ObjectNode vars = MAPPER.createObjectNode();

        return new NodeExecutionContext(
                node,
                config,
                secrets,
                vars,
                new VariableInterpolator(),
                MAPPER,
                PROPS,
                Mockito.mock(NodeLogger.class));
    }

    /**
     * A rejection is either a FAIL result or an ApiException
     * (the engine turns both into a failed node).
     */
    private void assertRejected(
            NodeExecutor executor,
            NodeExecutionContext c) {

        try {
            NodeResult result = executor.execute(c);

            assertThat(result.kind())
                    .as(executor.type())
                    .isEqualTo(NodeResult.Kind.FAIL);

        } catch (ApiException expected) {
            // Rejected directly by SsrfGuard.
        } catch (Exception other) {
            throw new AssertionError(
                    executor.type() + " threw " + other,
                    other);
        }
    }

    @Test
    void everyUserUrlHttpNodeRejectsCloudMetadata() {

        List<NodeExecutor> executors = List.of(
                new HttpRequestExecutor(PROPS),
                new ApiHealthCheckExecutor(PROPS),
                new ApiPollExecutor(PROPS),
                new DownloadFileExecutor(PROPS),
                new GraphQLRequestExecutor(PROPS),
                new HttpRetryExecutor(PROPS),
                new PaginatedApiRequestExecutor(PROPS),
                new RateLimitedRequestExecutor(PROPS),
                new UploadFileExecutor(PROPS),
                new WaitForHttpExecutor(PROPS));

        for (NodeExecutor executor : executors) {

            assertRejected(
                    executor,
                    ctx(
                            executor.type(),
                            Map.of(
                                    "url",
                                    METADATA,
                                    "method",
                                    "GET"),
                            Map.of()));

            assertRejected(
                    executor,
                    ctx(
                            executor.type(),
                            Map.of(
                                    "url",
                                    "http://127.0.0.1:8100/ai/complete"),
                            Map.of()));
        }
    }

    @Test
    void jiraNeverSendsStoredTokenToANodeSuppliedHost()
            throws Exception {

        Map<String, Object> config = Map.of(
                "siteUrl",
                "https://attacker.example.com",
                "project",
                "OPS",
                "summary",
                "x");

        NodeResult result =
                new JiraExecutor().execute(
                        ctx(
                                "jira",
                                config,
                                Map.of(
                                        "email",
                                        "a@b.c",
                                        "apiToken",
                                        "tok")));

        assertThat(result.kind())
                .isEqualTo(NodeResult.Kind.FAIL);

        assertThat(result.error())
        .contains("HTTPS Atlassian Cloud hostname");
    }

    @Test
    void githubIgnoresNodeBaseUrlAndRejectsPrivateCredentialBaseUrl()
            throws Exception {

        // Node-supplied baseUrl is ignored entirely;
        // a private base URL in the credential is refused.
        NodeResult result =
                new GitHubExecutor().execute(
                        ctx(
                                "github",
                                Map.of(
                                        "operation",
                                        "createIssue",
                                        "repo",
                                        "o/r",
                                        "title",
                                        "t",
                                        "baseUrl",
                                        "https://attacker.example.com"),
                                Map.of(
                                        "pat",
                                        "ghp_x",
                                        "baseUrl",
                                        "http://10.0.0.5")));

        assertThat(result.kind())
                .isEqualTo(NodeResult.Kind.FAIL);
    }

    @Test
    void databaseRedisAndMongoNodesRejectInternalTargets() {

        for (String host : new String[]{
                "postgres",
                "127.0.0.1",
                "10.0.0.5",
                "redis"}) {

            assertRejected(
                    new PostgreSqlExecutor(),
                    ctx(
                            "postgresql",
                            Map.of(
                                    "host",
                                    host,
                                    "database",
                                    "flowops",
                                    "username",
                                    "u",
                                    "sql",
                                    "select 1"),
                            Map.of()));

            assertRejected(
                    new DatabaseQueryExecutor(),
                    ctx(
                            "database_query",
                            Map.of(
                                    "databaseType",
                                    "postgresql",
                                    "host",
                                    host,
                                    "database",
                                    "flowops",
                                    "username",
                                    "u",
                                    "sql",
                                    "select 1"),
                            Map.of()));

            assertRejected(
                    new RedisGetExecutor(),
                    ctx(
                            "redis_get",
                            Map.of(
                                    "host",
                                    host,
                                    "key",
                                    "k"),
                            Map.of()));
        }

        assertRejected(
                new MongoDbExecutor(),
                ctx(
                        "mongodb",
                        Map.of(
                                "uri",
                                "mongodb://10.0.0.5:27017",
                                "database",
                                "d",
                                "collection",
                                "c",
                                "operation",
                                "find"),
                        Map.of()));
    }

    @Test
    void databaseNameCannotInjectJdbcParameters() {

        assertRejected(
                new PostgreSqlExecutor(),
                ctx(
                        "postgresql",
                        Map.of(
                                "host",
                                "93.184.216.34",
                                "database",
                                "app?loggerFile=/tmp/x",
                                "username",
                                "u",
                                "sql",
                                "select 1"),
                        Map.of()));
    }

    @Test
    void uploadFileRejectsArbitraryHostFilesystemTraversal() throws Exception {
        UploadFileExecutor executor = new UploadFileExecutor(PROPS);

        NodeResult etcPasswd = executor.execute(ctx(
                "upload_file",
                Map.of(
                        "url", "https://93.184.216.34/post",
                        "filePath", "/etc/passwd"),
                Map.of()));

        assertThat(etcPasswd.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(etcPasswd.error()).contains("no filePath or inlineContent configured");

        NodeResult traversal = executor.execute(ctx(
                "upload_file",
                Map.of(
                        "url", "https://93.184.216.34/post",
                        "filePath", "../../sensitive.txt"),
                Map.of()));

        assertThat(traversal.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(traversal.error()).contains("no filePath or inlineContent configured");
    }

    @Test
    void xmlParseRejectsDoctypeAndExternalEntityPayloads() throws Exception {
        XmlParseExecutor executor = new XmlParseExecutor();

        String xxePayload = "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]><foo>&xxe;</foo>";
        NodeResult result = executor.execute(ctx(
                "xml_parse",
                Map.of("input", xxePayload),
                Map.of()));

        assertThat(result.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(result.error()).contains("XML Parse failed");
    }

    @Test
    void discordRejectsInternalWebhookTargets() {
        DiscordExecutor executor = new DiscordExecutor();

        NodeResult metadataResult = executor.execute(ctx(
                "discord",
                Map.of("message", "test"),
                Map.of("webhookUrl", METADATA)));

        assertThat(metadataResult.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(metadataResult.error()).contains("Discord webhook target rejected");

        NodeResult localhostResult = executor.execute(ctx(
                "discord",
                Map.of("message", "test"),
                Map.of("webhookUrl", "http://127.0.0.1:8100/webhook")));

        assertThat(localhostResult.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(localhostResult.error()).contains("Discord webhook target rejected");
    }

    @Test
    void slackRejectsInternalWebhookTargets() {
        SlackExecutor executor = new SlackExecutor();

        NodeResult metadataResult = executor.execute(ctx(
                "slack",
                Map.of("message", "test"),
                Map.of("webhookUrl", METADATA)));

        assertThat(metadataResult.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(metadataResult.error()).contains("Slack webhook target rejected");

        NodeResult localhostResult = executor.execute(ctx(
                "slack",
                Map.of("message", "test"),
                Map.of("webhookUrl", "http://127.0.0.1:8100/webhook")));

        assertThat(localhostResult.kind()).isEqualTo(NodeResult.Kind.FAIL);
        assertThat(localhostResult.error()).contains("Slack webhook target rejected");
    }

    @Test
    void smtpProviderRejectsInternalAndMetadataHosts() {
        SmtpTransport mockTransport = Mockito.mock(SmtpTransport.class);
        SmtpProvider provider = new SmtpProvider(mockTransport);

        for (String host : new String[]{"169.254.169.254", "127.0.0.1", "localhost", "postgres"}) {
            DeliveryContext context = new DeliveryContext(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    Map.of(),
                    new DecryptedCredentials(Map.of(
                            "host", host,
                            "port", "587",
                            "fromAddress", "test@example.com")));

            ConnectionTestResult result = provider.testConnection(context);
            assertThat(result.success()).isFalse();
            assertThat(result.message())
                    .satisfiesAnyOf(
                            m -> assertThat(m).containsIgnoringCase("not allowed"),
                            m -> assertThat(m).containsIgnoringCase("forbidden"));
        }
    }
}