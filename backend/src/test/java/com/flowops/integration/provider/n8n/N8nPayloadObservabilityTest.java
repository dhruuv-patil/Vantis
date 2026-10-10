package com.flowops.integration.provider.n8n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.security.http.PayloadTooLargeException;
import com.flowops.integration.provider.ExecutionStatus;
import com.flowops.integration.provider.ExternalExecution;
import com.flowops.integration.provider.ExternalNodeExecution;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.ProviderConnectionException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class N8nPayloadObservabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode loadFixture(String fixtureName) throws Exception {
        String path = "/fixtures/n8n/" + fixtureName;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + path);
            }
            return MAPPER.readTree(in);
        }
    }

    @Test
    @DisplayName("Fixture: single_item_success - extracts item counts, timestamps, and zero-PII schema hash")
    void singleItemSuccessFixture() throws Exception {
        JsonNode json = loadFixture("single_item_success.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-101", "wf-orders-01");

        assertThat(nodes).hasSize(2);

        // Node 1: Webhook
        ExternalNodeExecution webhook = nodes.get(0);
        assertThat(webhook.name()).isEqualTo("Webhook");
        assertThat(webhook.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(webhook.itemCount()).isEqualTo(1);
        assertThat(webhook.dataCompleteness()).isEqualTo("COMPLETE");
        assertThat(webhook.outputSignature()).isNotNull();
        assertThat(webhook.outputSignature().get("hash")).asString().hasSize(64);
        @SuppressWarnings("unchecked")
        Map<String, Object> webhookShape = (Map<String, Object>) webhook.outputSignature().get("shape");
        assertThat(webhookShape).containsEntry("orderId", "string");
        assertThat(webhookShape).containsEntry("amount", "number");
        assertThat(webhookShape).containsEntry("status", "string");

        // Node 2: Enrich Customer
        ExternalNodeExecution enrich = nodes.get(1);
        assertThat(enrich.name()).isEqualTo("Enrich Customer");
        assertThat(enrich.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(enrich.itemCount()).isEqualTo(1);
        assertThat(enrich.startedAt()).isEqualTo(Instant.ofEpochMilli(1709287200020L));
        assertThat(enrich.finishedAt()).isEqualTo(Instant.ofEpochMilli(1709287200230L));
    }

    @Test
    @DisplayName("Fixture: multi_item_success - extracts multi-item count (5) and inventory schema")
    void multiItemSuccessFixture() throws Exception {
        JsonNode json = loadFixture("multi_item_success.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-102", "wf-sync-inventory");

        assertThat(nodes).hasSize(2);
        ExternalNodeExecution fetchInventory = nodes.get(1);

        assertThat(fetchInventory.name()).isEqualTo("Fetch Inventory");
        assertThat(fetchInventory.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(fetchInventory.itemCount()).isEqualTo(5);
        assertThat(fetchInventory.outputSignature()).isNotNull();
        assertThat(fetchInventory.outputSignature().get("itemCount")).isEqualTo(5);

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) fetchInventory.outputSignature().get("keys");
        assertThat(keys).containsExactly("available", "qty", "sku", "warehouse");

        @SuppressWarnings("unchecked")
        Map<String, Object> shape = (Map<String, Object>) fetchInventory.outputSignature().get("shape");
        assertThat(shape).containsEntry("available", "boolean");
        assertThat(shape).containsEntry("qty", "number");
        assertThat(shape).containsEntry("sku", "string");
        assertThat(shape).containsEntry("warehouse", "string");
    }

    @Test
    @DisplayName("Fixture: zero_items_success - handles 0-item outputs cleanly (itemCount=0 != null)")
    void zeroItemsSuccessFixture() throws Exception {
        JsonNode json = loadFixture("zero_items_success.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-103", "wf-filter-leads");

        assertThat(nodes).hasSize(2);
        ExternalNodeExecution filter = nodes.get(1);

        assertThat(filter.name()).isEqualTo("Filter High Value");
        assertThat(filter.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        // Distinguishing 0 from null is critical for zero-output detectors
        assertThat(filter.itemCount()).isNotNull().isEqualTo(0);
        assertThat(filter.dataCompleteness()).isEqualTo("COMPLETE");
        assertThat(filter.outputSignature()).isNotNull();
        assertThat(filter.outputSignature().get("itemCount")).isEqualTo(0);
        assertThat(filter.outputSignature().get("hash")).isEqualTo("empty");
    }

    @Test
    @DisplayName("Fixture: error_with_details - extracts HTTP 404, NodeApiError, and error message")
    void errorWithDetailsFixture() throws Exception {
        JsonNode json = loadFixture("error_with_details.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-104", "wf-sync-crm");

        assertThat(nodes).hasSize(2);
        ExternalNodeExecution failedNode = nodes.get(1);

        assertThat(failedNode.name()).isEqualTo("Call External API");
        assertThat(failedNode.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failedNode.httpStatus()).isEqualTo(404);
        assertThat(failedNode.errorCode()).isEqualTo("NodeApiError");
        assertThat(failedNode.errorMessage()).contains("Resource not found (404)");
        assertThat(failedNode.itemCount()).isNull();
    }

    @Test
    @DisplayName("Fixture: error_without_details - falls back to execution-level error when runData is empty")
    void errorWithoutDetailsFixture() throws Exception {
        JsonNode json = loadFixture("error_without_details.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-105", "wf-crashed");

        assertThat(nodes).hasSize(1);
        ExternalNodeExecution execNode = nodes.get(0);

        assertThat(execNode.name()).isEqualTo("Execution");
        assertThat(execNode.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execNode.errorMessage()).contains("Workflow execution crashed before any node completed");
    }

    @Test
    @DisplayName("Fixture: loops_multi_run - generates distinct executions for each loop iteration with runIndex")
    void loopsMultiRunFixture() throws Exception {
        JsonNode json = loadFixture("loops_multi_run.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-106", "wf-looping");

        // 1 Trigger + 3 loop iterations = 4
        assertThat(nodes).hasSize(4);

        ExternalNodeExecution loopRun0 = nodes.get(1);
        ExternalNodeExecution loopRun1 = nodes.get(2);
        ExternalNodeExecution loopRun2 = nodes.get(3);

        assertThat(loopRun0.externalId()).isEqualTo("Loop Over Items:0");
        assertThat(loopRun0.runIndex()).isEqualTo(0);
        assertThat(loopRun0.itemCount()).isEqualTo(1);

        assertThat(loopRun1.externalId()).isEqualTo("Loop Over Items:1");
        assertThat(loopRun1.runIndex()).isEqualTo(1);
        assertThat(loopRun1.itemCount()).isEqualTo(1);

        assertThat(loopRun2.externalId()).isEqualTo("Loop Over Items:2");
        assertThat(loopRun2.runIndex()).isEqualTo(2);
        assertThat(loopRun2.itemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Fixture: multi_branch_if - correctly sums items across all main branches")
    void multiBranchIfFixture() throws Exception {
        JsonNode json = loadFixture("multi_branch_if.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-107", "wf-branching");

        assertThat(nodes).hasSize(3);
        ExternalNodeExecution ifNode = nodes.get(1);

        // IF node branch 0 has 1 item, branch 1 has 0 items -> total is 1
        assertThat(ifNode.name()).isEqualTo("IF Approved");
        assertThat(ifNode.itemCount()).isEqualTo(1);
        assertThat(ifNode.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Fixture: subworkflow_execution - links child execution ID and child workflow ID in metadata")
    void subworkflowExecutionFixture() throws Exception {
        JsonNode json = loadFixture("subworkflow_execution.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-108", "wf-parent-orchestrator");

        assertThat(nodes).hasSize(2);
        ExternalNodeExecution subNode = nodes.get(1);

        assertThat(subNode.name()).isEqualTo("Execute Subworkflow");
        assertThat(subNode.metadata()).containsEntry("nodeType", "subworkflow");
        assertThat(subNode.metadata()).containsEntry("subExecutionId", "exec-child-990");
        assertThat(subNode.metadata()).containsEntry("subWorkflowId", "wf-child-processor");
    }

    @Test
    @DisplayName("Fixture: pruned_data - marks dataCompleteness as DATA_PRUNED and itemCount as null")
    void prunedDataFixture() throws Exception {
        JsonNode json = loadFixture("pruned_data.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-109", "wf-high-volume");

        assertThat(nodes).hasSize(1);
        ExternalNodeExecution prunedNode = nodes.get(0);

        assertThat(prunedNode.name()).isEqualTo("High Volume Source");
        assertThat(prunedNode.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(prunedNode.itemCount()).isNull();
        assertThat(prunedNode.dataCompleteness()).isEqualTo("DATA_PRUNED");
        assertThat(prunedNode.outputSignature()).isNull();
    }

    @Test
    @DisplayName("Fixture: wait_webhook_execution - recognizes WAITING status for paused runs")
    void waitWebhookExecutionFixture() throws Exception {
        JsonNode json = loadFixture("wait_webhook_execution.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-110", "wf-human-approval");

        assertThat(nodes).hasSize(2);
        ExternalNodeExecution waitNode = nodes.get(1);

        assertThat(waitNode.name()).isEqualTo("Wait For Approval");
        assertThat(waitNode.status()).isEqualTo(ExecutionStatus.WAITING);
    }

    @Test
    @DisplayName("Fixture: version_0_x_legacy - successfully maps n8n 0.x legacy payload format")
    void version0xLegacyFixture() throws Exception {
        JsonNode json = loadFixture("version_0_x_legacy.json");
        List<ExternalNodeExecution> nodes = N8nNodeExecutionMapper.toExternal(json, "exec-099", "wf-legacy-0");

        assertThat(nodes).hasSize(1);
        ExternalNodeExecution startNode = nodes.get(0);

        assertThat(startNode.name()).isEqualTo("Start");
        assertThat(startNode.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(startNode.itemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Structural signatures: identical schemas produce identical hashes regardless of values or key ordering")
    void structuralSignatureStability() throws Exception {
        String itemA = """
                {
                  "userId": "123",
                  "email": "alice@example.com",
                  "active": true,
                  "score": 99.5
                }
                """;

        String itemB = """
                {
                  "score": 42.0,
                  "active": false,
                  "userId": "999",
                  "email": "bob@another.org"
                }
                """;

        String itemDrifted = """
                {
                  "userId": 123,
                  "email": "alice@example.com",
                  "active": true,
                  "score": 99.5,
                  "newField": "drift"
                }
                """;

        Map<String, Object> sigA = N8nNodeExecutionMapper.extractStructuralSignature(MAPPER.readTree(itemA), 1);
        Map<String, Object> sigB = N8nNodeExecutionMapper.extractStructuralSignature(MAPPER.readTree(itemB), 1);
        Map<String, Object> sigDrift = N8nNodeExecutionMapper.extractStructuralSignature(MAPPER.readTree(itemDrifted), 1);

        // A and B have the same schema -> identical hash
        assertThat(sigA.get("hash")).isEqualTo(sigB.get("hash"));

        // Drifted schema -> different hash
        assertThat(sigA.get("hash")).isNotEqualTo(sigDrift.get("hash"));
    }

    @Test
    @DisplayName("Structural signatures: deep nesting is bounded at 3 levels without memory exhaustion")
    void structuralSignatureDepthBounding() throws Exception {
        String deepJson = """
                {
                  "l1": {
                    "l2": {
                      "l3": {
                        "l4": {
                          "l5": "too deep"
                        }
                      }
                    }
                  }
                }
                """;

        Map<String, Object> sig = N8nNodeExecutionMapper.extractStructuralSignature(MAPPER.readTree(deepJson), 1);
        assertThat(sig).isNotNull();
        assertThat(sig.get("truncated")).isEqualTo(true);
        assertThat(sig.get("hash")).isNotNull();
    }

    @Test
    @DisplayName("N8nProvider handles PayloadTooLargeException by returning safe PAYLOAD_TRUNCATED node")
    void n8nProviderHandlesPayloadTooLarge() {
        N8nClient client = mock(N8nClient.class);
        N8nProvider provider = new N8nProvider(client);

        IntegrationContext context = mock(IntegrationContext.class);
        ExternalExecution execution = new ExternalExecution(
                "exec-huge",
                "wf-huge",
                ExecutionStatus.SUCCEEDED,
                Instant.now(),
                Instant.now().plusSeconds(5),
                Map.of());

        when(client.getExecutionData(context, "exec-huge"))
                .thenThrow(new ProviderConnectionException(
                        com.flowops.integration.provider.IntegrationType.N8N,
                        "Payload too large",
                        new PayloadTooLargeException(10_000_000L)));

        List<ExternalNodeExecution> nodes = provider.fetchNodeExecutions(context, execution);

        assertThat(nodes).hasSize(1);
        ExternalNodeExecution truncatedNode = nodes.get(0);
        assertThat(truncatedNode.dataCompleteness()).isEqualTo("PAYLOAD_TRUNCATED");
        assertThat(truncatedNode.errorCode()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(truncatedNode.itemCount()).isNull();
        assertThat(truncatedNode.outputSignature()).containsEntry("truncated", true);
    }
}
