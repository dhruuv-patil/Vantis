package com.flowops.reliability.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.ExecutionNode;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.TriggerType;
import com.flowops.domain.WorkflowExecution;
import com.flowops.execution.ExecutionStore;
import com.flowops.repository.ExecutionEventRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Slice tests for {@link TelemetryCollector}. {@link ExecutionStore} (the engine's
 * persistence boundary) and the metrics repository are mocked; the extractor and a
 * real {@link ObjectMapper} are used so the derived rows are genuinely built.
 *
 * <p>The invariants under test: telemetry is captured only for executed
 * (SUCCEEDED/FAILED/SKIPPED) nodes at terminal state — never in-flight ones; a
 * recapture is idempotent; and the stored observation carries sizes + structure only,
 * never any payload value.
 */
class TelemetryCollectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OutputSignatureExtractor extractor =
            new OutputSignatureExtractor();

    private final ExecutionStore store =
            mock(ExecutionStore.class);

    private final NodeExecutionMetricRepository metrics =
            mock(NodeExecutionMetricRepository.class);

    private final ExecutionEventRepository events =
            mock(ExecutionEventRepository.class);

    private final IntegrationWorkflowRepository workflows =
            mock(IntegrationWorkflowRepository.class);

    private TelemetryCollector collector() {
        return new TelemetryCollector(
                store,
                metrics,
                mapper,
                extractor,
                events,
                workflows);
    }

    private WorkflowExecution execution(UUID executionId) {
        return WorkflowExecution.create(
                UUID.randomUUID(), // organization_id
                UUID.randomUUID(), // workflow_id
                UUID.randomUUID(), // workflow_version_id
                3,
                TriggerType.MANUAL,
                mapper.createObjectNode(),
                null);
    }

    @Test
    void capturesOnlyExecutedNodesAtTerminalState() {
        UUID execId = UUID.randomUUID();

        WorkflowExecution exec = execution(execId);

        when(store.getExecution(execId))
                .thenReturn(exec);

        when(store.getNodes(execId))
                .thenReturn(List.of(
                        executedNode(
                                execId,
                                "n1",
                                "manual_trigger",
                                success(
                                        mapper.createObjectNode()
                                                .put("ok", true))),

                        executedNode(
                                execId,
                                "n2",
                                "transform",
                                skip()),

                        executedNode(
                                execId,
                                "n3",
                                "http_request",
                                fail(
                                        "Timeout on POST https://api.example.com")),

                        executedNode(
                                execId,
                                "n4",
                                "delay",
                                pending())));

        collector().capture(execId);

        verify(metrics)
                .deleteByExecutionId(execId);

        ArgumentCaptor<NodeExecutionMetric> captor =
                ArgumentCaptor.forClass(NodeExecutionMetric.class);

        verify(metrics, org.mockito.Mockito.times(3))
                .save(captor.capture());

        List<NodeExecutionMetric> saved =
                captor.getAllValues();

        // Only executed nodes (SUCCEEDED/FAILED/SKIPPED) are captured;
        // PENDING is skipped.
        assertThat(saved)
                .hasSize(3);

        assertThat(saved)
                .allSatisfy(m ->
                        assertThat(m.getStatus())
                                .isIn(
                                        NodeRunStatus.SUCCEEDED,
                                        NodeRunStatus.FAILED,
                                        NodeRunStatus.SKIPPED));

        NodeExecutionMetric skipped =
                saved.stream()
                        .filter(m -> m.getNodeId().equals("n2"))
                        .findFirst()
                        .orElseThrow();

        assertThat(skipped.getStatus())
                .isEqualTo(NodeRunStatus.SKIPPED);
    }

    @Test
    void succeededNodeRecordsTimingSizesSignatureAndRetryCount() {
        UUID execId = UUID.randomUUID();

        WorkflowExecution exec = execution(execId);

        when(store.getExecution(execId))
                .thenReturn(exec);

        ExecutionNode node =
                successWithRetry(
                        execId,
                        "n1",
                        "transform",
                        mapper.createObjectNode()
                                .put("company_size", 1200)
                                .put("name", "Vantis"));

        when(store.getNodes(execId))
                .thenReturn(List.of(node));

        collector().capture(execId);

        ArgumentCaptor<NodeExecutionMetric> captor =
                ArgumentCaptor.forClass(NodeExecutionMetric.class);

        verify(metrics)
                .save(captor.capture());

        NodeExecutionMetric m =
                captor.getValue();

        assertThat(m.getStatus())
                .isEqualTo(NodeRunStatus.SUCCEEDED);

        assertThat(m.getWorkflowVersion())
                .isEqualTo(3);

        assertThat(m.getRetryCount())
                .isEqualTo(2);

        assertThat(m.getDurationMs())
                .isNotNull();

        assertThat(m.getOutputSize())
                .isPositive();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sig =
                (java.util.Map<String, Object>)
                        m.getOutputSignature();

        assertThat(sig.get("form"))
                .isEqualTo("OBJECT");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> fields =
                (java.util.Map<String, Object>)
                        sig.get("fields");

        assertThat(fields.get("company_size"))
                .isEqualTo(
                        java.util.Map.of(
                                "type",
                                "number",
                                "null",
                                false));

        // The signature and sizes must never leak the actual payload.
        assertThat(m.getOutputSignature().toString())
                .doesNotContain("Vantis");

        assertThat(sig.toString())
                .doesNotContain("1200");
    }

    @Test
    void failedNodeRecordsSanitizedErrorOnly() {
        UUID execId = UUID.randomUUID();

        WorkflowExecution exec = execution(execId);

        when(store.getExecution(execId))
                .thenReturn(exec);

        String longError =
                "Connection refused "
                        + "x".repeat(900)
                        + "\n  second line";

        when(store.getNodes(execId))
                .thenReturn(List.of(
                        executedNode(
                                execId,
                                "n1",
                                "http_request",
                                fail(longError))));

        collector().capture(execId);

        ArgumentCaptor<NodeExecutionMetric> captor =
                ArgumentCaptor.forClass(NodeExecutionMetric.class);

        verify(metrics)
                .save(captor.capture());

        NodeExecutionMetric m =
                captor.getValue();

        assertThat(m.getStatus())
                .isEqualTo(NodeRunStatus.FAILED);

        assertThat(m.getErrorMessage())
                .startsWith("Connection refused");

        assertThat(m.getErrorMessage().length())
                .isLessThanOrEqualTo(501);

        assertThat(m.getErrorMessage().contains("\n"))
                .isFalse();

        assertThat(m.getOutputSignature())
                .isNull();
    }

    @Test
    void recaptureIsIdempotentBecausePriorRowsAreDeleted() {
        UUID execId = UUID.randomUUID();

        when(store.getExecution(execId))
                .thenReturn(execution(execId));

        when(store.getNodes(execId))
                .thenReturn(List.of(
                        executedNode(
                                execId,
                                "n1",
                                "manual_trigger",
                                success(
                                        mapper.createObjectNode()))));

        collector().capture(execId);
        collector().capture(execId);

        verify(
                metrics,
                org.mockito.Mockito.times(2))
                .deleteByExecutionId(execId);

        verify(
                metrics,
                org.mockito.Mockito.times(2))
                .save(any(NodeExecutionMetric.class));
    }

    // ---- helpers ----------------------------------------------------------

    private ExecutionNode executedNode(
            UUID execId,
            String nodeId,
            String type,
            Result result) {

        ExecutionNode node =
                ExecutionNode.create(
                        execId,
                        nodeId,
                        type,
                        type);

        if (result.status() == NodeRunStatus.SUCCEEDED) {

            node.markRunning(
                    mapper.createObjectNode());

            node.succeed(
                    result.output(),
                    java.util.List.of());

        } else if (result.status() == NodeRunStatus.FAILED) {

            node.markRunning(
                    mapper.createObjectNode());

            node.fail(
                    result.error());

        } else if (result.status() == NodeRunStatus.SKIPPED) {

            node.skip();
        }

        return node;
    }

    private Result success(JsonNode output) {
        return new Result(
                NodeRunStatus.SUCCEEDED,
                output,
                null);
    }

    private Result fail(String error) {
        return new Result(
                NodeRunStatus.FAILED,
                null,
                error);
    }

    private Result skip() {
        return new Result(
                NodeRunStatus.SKIPPED,
                null,
                null);
    }

    private Result pending() {
        return new Result(
                NodeRunStatus.PENDING,
                null,
                null);
    }

    private ExecutionNode successWithRetry(
            UUID execId,
            String nodeId,
            String type,
            JsonNode output) {

        ExecutionNode node =
                ExecutionNode.create(
                        execId,
                        nodeId,
                        type,
                        type);

        // Simulate 3 attempts: markRunning increments attempt each time.
        node.markRunning(
                mapper.createObjectNode());

        node.markRunning(
                mapper.createObjectNode());

        node.markRunning(
                mapper.createObjectNode());

        node.succeed(
                output,
                java.util.List.of());

        return node;
    }

    private record Result(
            NodeRunStatus status,
            JsonNode output,
            String error) {
    }
}