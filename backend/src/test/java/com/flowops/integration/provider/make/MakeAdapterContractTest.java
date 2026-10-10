package com.flowops.integration.provider.make;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.integration.provider.ExecutionStatus;
import com.flowops.integration.provider.ExternalExecution;
import com.flowops.integration.provider.ExternalWorkflow;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MakeAdapterContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Maps executions with standard { executions: [...] } envelope and numeric status")
    void testStandardExecutionsEnvelopeWithNumericStatus() throws Exception {
        String json = """
                {
                  "executions": [
                    {
                      "id": 1001,
                      "scenarioId": 2001,
                      "status": 1,
                      "startedAt": "2026-10-10T08:00:00Z",
                      "finishedAt": "2026-10-10T08:00:05Z",
                      "duration": 5000,
                      "dataSize": 1024
                    },
                    {
                      "id": 1002,
                      "scenarioId": 2001,
                      "status": 3,
                      "startedAt": "2026-10-10T08:05:00Z",
                      "finishedAt": "2026-10-10T08:05:02Z",
                      "error": {
                        "message": "Connection refused to webhook endpoint",
                        "type": "ConnectionError"
                      }
                    }
                  ]
                }
                """;
        JsonNode node = mapper.readTree(json);
        List<ExternalExecution> result = MakeExecutionMapper.toExternal(node);

        assertThat(result).hasSize(2);

        ExternalExecution exec1 = result.get(0);
        assertThat(exec1.externalId()).isEqualTo("1001");
        assertThat(exec1.workflowExternalId()).isEqualTo("2001");
        assertThat(exec1.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(exec1.metadata()).containsEntry("durationMs", 5000L);

        ExternalExecution exec2 = result.get(1);
        assertThat(exec2.externalId()).isEqualTo("1002");
        assertThat(exec2.workflowExternalId()).isEqualTo("2001");
        assertThat(exec2.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(exec2.metadata())
                .containsEntry("errorMessage", "Connection refused to webhook endpoint")
                .containsEntry("errorCode", "ConnectionError");
    }

    @Test
    @DisplayName("Maps executions with scenarioLogs and logs envelopes and string UUID IDs")
    void testAlternativeEnvelopesAndStringIds() throws Exception {
        String scenarioLogsJson = """
                {
                  "scenarioLogs": [
                    {
                      "id": "exec_abc_123",
                      "scenario_id": "sc_xyz_789",
                      "status": "warning",
                      "startedAt": "2026-10-10T09:00:00Z",
                      "finishedAt": "2026-10-10T09:00:01Z"
                    }
                  ]
                }
                """;
        JsonNode node1 = mapper.readTree(scenarioLogsJson);
        List<ExternalExecution> res1 = MakeExecutionMapper.toExternal(node1);
        assertThat(res1).hasSize(1);
        assertThat(res1.get(0).externalId()).isEqualTo("exec_abc_123");
        assertThat(res1.get(0).workflowExternalId()).isEqualTo("sc_xyz_789");
        assertThat(res1.get(0).status()).isEqualTo(ExecutionStatus.FAILED);

        String rootArrayJson = """
                [
                  {
                    "executionId": "exec_root_456",
                    "scenario": {
                      "id": "sc_root_123"
                    },
                    "status": "success",
                    "timestamp": 1700000000000
                  }
                ]
                """;
        JsonNode node2 = mapper.readTree(rootArrayJson);
        List<ExternalExecution> res2 = MakeExecutionMapper.toExternal(node2);
        assertThat(res2).hasSize(1);
        assertThat(res2.get(0).externalId()).isEqualTo("exec_root_456");
        assertThat(res2.get(0).workflowExternalId()).isEqualTo("sc_root_123");
        assertThat(res2.get(0).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Maps Make workflow scenarios across different envelopes and ID types")
    void testMakeWorkflowMapper() throws Exception {
        String scenariosJson = """
                {
                  "scenarios": [
                    {
                      "id": 5544,
                      "name": "Sync Stripe to QuickBooks",
                      "status": "ACTIVE"
                    },
                    {
                      "id": "sc_custom_uuid",
                      "name": "Hubspot Inbound Leads",
                      "status": "INACTIVE"
                    }
                  ]
                }
                """;
        JsonNode node = mapper.readTree(scenariosJson);
        List<ExternalWorkflow> workflows = MakeWorkflowMapper.toExternal(node);

        assertThat(workflows).hasSize(2);
        assertThat(workflows.get(0).externalId()).isEqualTo("5544");
        assertThat(workflows.get(0).name()).isEqualTo("Sync Stripe to QuickBooks");
        assertThat(workflows.get(1).externalId()).isEqualTo("sc_custom_uuid");
        assertThat(workflows.get(1).name()).isEqualTo("Hubspot Inbound Leads");
    }
}
