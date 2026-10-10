package com.flowops.reliability.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.ExecutionEvent;
import com.flowops.domain.ExecutionNode;
import com.flowops.domain.IntegrationWorkflow;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.WorkflowExecution;
import com.flowops.execution.ExecutionStore;
import com.flowops.repository.ExecutionEventRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryCollector {

    private static final Logger log =
            LoggerFactory.getLogger(TelemetryCollector.class);

    private final ExecutionStore store;
    private final NodeExecutionMetricRepository metrics;
    private final ObjectMapper mapper;
    private final OutputSignatureExtractor signatureExtractor;
    private final ExecutionEventRepository eventRepo;
    private final IntegrationWorkflowRepository workflowRepo;

    public TelemetryCollector(
            ExecutionStore store,
            NodeExecutionMetricRepository metrics,
            ObjectMapper mapper,
            OutputSignatureExtractor signatureExtractor,
            ExecutionEventRepository eventRepo,
            IntegrationWorkflowRepository workflowRepo) {
        this.store = store;
        this.metrics = metrics;
        this.mapper = mapper;
        this.signatureExtractor = signatureExtractor;
        this.eventRepo = eventRepo;
        this.workflowRepo = workflowRepo;
    }

    /**
     * Writes telemetry for a terminal execution. Idempotent: prior rows for the same
     * execution are replaced, so an engine that re-enters a terminal transition does
     * not duplicate observations.
     */
    @Transactional
    public void capture(UUID executionId) {
        WorkflowExecution execution =
                store.getExecution(executionId);

        List<ExecutionNode> nodes =
                store.getNodes(executionId);

        metrics.deleteByExecutionId(executionId);

        int captured = 0;

        for (ExecutionNode node : nodes) {
            if (node.getStatus() != NodeRunStatus.SUCCEEDED
                    && node.getStatus() != NodeRunStatus.FAILED
                    && node.getStatus() != NodeRunStatus.SKIPPED) {
                continue;
            }

            metrics.save(buildMetric(execution, node));
            captured++;
        }

        log.debug(
                "Captured reliability telemetry for execution {} ({} nodes)",
                executionId,
                captured);
    }

    /**
     * Captures telemetry from external execution events for a given
     * {@link IntegrationWorkflow} (referenced by its UUID).
     *
     * <p>
     * Reads committed {@link ExecutionEvent} rows for the external workflow,
     * groups them by {@code execution_external_id} into synthetic run UUIDs
     * (deterministic: {@code UUID.nameUUIDFromBytes(integrationId + ":" + executionExternalId)}),
     * and writes one {@link NodeExecutionMetric} per event.
     *
     * <p>
     * Idempotent: deletes any prior metrics for the same (workflow, run) before rewriting.
     * No {@code outputSignature} is recorded (external outputs are never stored).
     *
     * @param externalWorkflowRef the {@code IntegrationWorkflow.id} (not a Workflow.id)
     * @param since only events with {@code createdAt >= since} are considered; null
     *              means all events (first sync)
     */
    @Transactional
    public void captureExternal(
            UUID externalWorkflowRef,
            Instant since) {

        IntegrationWorkflow extWf =
                workflowRepo.findById(externalWorkflowRef)
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "External workflow not found: "
                                                + externalWorkflowRef));

        List<ExecutionEvent> events;

        if (since != null) {
            events =
                    eventRepo
                            .findByIntegrationIdAndProviderWorkflowIdAndCreatedAtAfter(
                                    extWf.getIntegrationId(),
                                    extWf.getProviderWorkflowId(),
                                    since);
        } else {
            events =
                    eventRepo
                            .findByIntegrationIdAndProviderWorkflowId(
                                    extWf.getIntegrationId(),
                                    extWf.getProviderWorkflowId());
        }

        if (events.isEmpty()) {
            log.debug(
                    "No new external events for workflow {}",
                    externalWorkflowRef);
            return;
        }

        Map<String, List<ExecutionEvent>> byExecution =
                new java.util.LinkedHashMap<>();

        for (ExecutionEvent event : events) {
            byExecution
                    .computeIfAbsent(
                            event.getExecutionExternalId(),
                            k -> new java.util.ArrayList<>())
                    .add(event);
        }

        int captured = 0;

        for (Map.Entry<String, List<ExecutionEvent>> entry :
                byExecution.entrySet()) {

            String executionExternalId =
                    entry.getKey();

            List<ExecutionEvent> execEvents =
                    entry.getValue();

            UUID runUuid =
                    uuidFromParts(
                            extWf.getIntegrationId(),
                            executionExternalId);

            metrics.deleteByExecutionId(runUuid);

            for (ExecutionEvent event : execEvents) {
                NodeExecutionMetric metric =
                        buildExternalMetric(
                                extWf,
                                runUuid,
                                event);

                metrics.save(metric);
                captured++;
            }
        }

        log.debug(
                "Captured external telemetry for workflow {} ({} nodes in {} runs)",
                externalWorkflowRef,
                captured,
                byExecution.size());
    }

    private NodeExecutionMetric buildExternalMetric(
            IntegrationWorkflow extWf,
            UUID runUuid,
            ExecutionEvent event) {

        NodeExecutionMetric metric =
                NodeExecutionMetric.create(
                        extWf.getOrganizationId(),
                        extWf.getId(),
                        0,
                        runUuid,
                        event.getStepExternalId(),
                        "externalStep",
                        mapStatus(event.getStatus()));

        if (event.getStartedAt() != null
                || event.getFinishedAt() != null
                || event.getDurationMs() != null) {

            metric.recordTiming(
                    event.getStartedAt(),
                    event.getFinishedAt(),
                    event.getDurationMs());
        }

        Integer sizeOrCount = event.getItemCount() != null
                ? event.getItemCount()
                : event.getOutputSize();

        metric.recordSizes(
                event.getInputSize(),
                sizeOrCount);

        if (event.getOutputSignature() != null) {
            metric.recordSignature(event.getOutputSignature());
        }

        if (event.getHttpStatus() != null) {
            metric.recordHttpStatus(event.getHttpStatus());
        }

        if ("FAILED".equalsIgnoreCase(event.getStatus())
                || "ERROR".equalsIgnoreCase(event.getStatus())) {

            metric.recordError(
                    event.getErrorType(),
                    safe(event.getErrorMessage()));
        }

        if (event.getRetryCount() != null) {
            metric.recordRetryCount(
                    event.getRetryCount());
        }

        return metric;
    }

    private NodeRunStatus mapStatus(String status) {
        return switch (
                status == null
                        ? ""
                        : status.toUpperCase()) {

            case "SUCCEEDED" -> NodeRunStatus.SUCCEEDED;
            case "FAILED" -> NodeRunStatus.FAILED;
            case "WAITING" -> NodeRunStatus.WAITING;
            case "RUNNING" -> NodeRunStatus.RUNNING;
            case "SKIPPED" -> NodeRunStatus.SKIPPED;
            case "CANCELED" -> NodeRunStatus.FAILED;
            default -> NodeRunStatus.FAILED;
        };
    }

    private UUID uuidFromParts(
            UUID integrationId,
            String executionExternalId) {

        String combined =
                integrationId.toString()
                        + ":"
                        + executionExternalId;

        return UUID.nameUUIDFromBytes(
                combined.getBytes());
    }

    private NodeExecutionMetric buildMetric(
            WorkflowExecution execution,
            ExecutionNode node) {

        NodeExecutionMetric metric =
                NodeExecutionMetric.create(
                        execution.getOrganizationId(),
                        execution.getWorkflowId(),
                        execution.getVersionNumber(),
                        execution.getId(),
                        node.getNodeId(),
                        node.getNodeType(),
                        node.getStatus());

        if (node.getStatus() == NodeRunStatus.SUCCEEDED
                || node.getStatus() == NodeRunStatus.FAILED) {

            metric.recordTiming(
                    node.getStartedAt(),
                    node.getFinishedAt(),
                    node.getDurationMs());

            metric.recordSizes(
                    serializedBytes(node.getInput()),
                    serializedBytes(node.getOutput()));
        }

        if (node.getStatus() == NodeRunStatus.FAILED) {
            metric.recordError(
                    errorType(node.getError()),
                    safe(node.getError()));
        }

        if (node.getStatus() == NodeRunStatus.SUCCEEDED) {
            metric.recordSignature(
                    signatureExtractor.signature(
                            node.getOutput()));

            metric.recordRetryCount(
                    Math.max(
                            0,
                            node.getAttempt() - 1));
        }

        return metric;
    }

    private Integer serializedBytes(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return 0;
        }

        try {
            return mapper.writeValueAsBytes(payload).length;
        } catch (Exception e) {
            return 0;
        }
    }

    /** A coarse, safe failure class for the {@code error_type} column. */
    private String errorType(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }

        String firstToken =
                error.trim().split("\\s+")[0];

        return firstToken.length() > 64
                ? firstToken.substring(0, 64)
                : firstToken;
    }

    /** Collapses whitespace and bounds length, mirroring the engine's {@code safe()}. */
    private String safe(String message) {
        if (message == null || message.isBlank()) {
            return "Unexpected error.";
        }

        String cleaned =
                message
                        .replaceAll("\\s+", " ")
                        .trim();

        return cleaned.length() > 500
                ? cleaned.substring(0, 500) + "…"
                : cleaned;
    }
}