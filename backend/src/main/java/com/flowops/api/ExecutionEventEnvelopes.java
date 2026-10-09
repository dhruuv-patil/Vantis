package com.flowops.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowops.domain.ExecutionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response envelopes for the execution event ingestion API.
 */
public final class ExecutionEventEnvelopes {

    private ExecutionEventEnvelopes() {
    }

    // =========================================================================
    // Single event ingestion
    // =========================================================================

    /** {@code POST /api/v1/execution-events} */
    public record IngestExecutionEventRequest(
            String source,
            String workflowExternalId,
            String executionExternalId,
            String stepExternalId,
            String stepName,
            String status,
            Instant timestamp,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            Integer retryCount,
            Integer inputSize,
            Integer outputSize,
            Integer itemCount,
            Map<String, Object> outputSignature,
            Integer httpStatus,
            String dataCompleteness,
            Integer runIndex,
            String errorType,
            String errorMessage,
            Map<String, Object> metadata,
            String workflowName,
            /** Owning Integration — always set on provider-synced rows (idempotency identity). */
            UUID integrationId,
            /** IntegrationWorkflow.id on the light path; becomes {@code workflow_id}. */
            UUID externalWorkflowRef) {

        /** Backwards-compatible 19-argument constructor for legacy callers. */
        public IngestExecutionEventRequest(
                String source,
                String workflowExternalId,
                String executionExternalId,
                String stepExternalId,
                String stepName,
                String status,
                Instant timestamp,
                Instant startedAt,
                Instant finishedAt,
                Long durationMs,
                Integer retryCount,
                Integer inputSize,
                Integer outputSize,
                String errorType,
                String errorMessage,
                Map<String, Object> metadata,
                String workflowName,
                UUID integrationId,
                UUID externalWorkflowRef) {
            this(source, workflowExternalId, executionExternalId, stepExternalId, stepName, status,
                    timestamp, startedAt, finishedAt, durationMs, retryCount, inputSize, outputSize,
                    null, null, null, null, 0, errorType, errorMessage, metadata, workflowName,
                    integrationId, externalWorkflowRef);
        }
    }

    /** Response for a single ingested event. */
    public record ExecutionEventResponse(ExecutionEvent event) {
        public static ExecutionEventResponse of(com.flowops.domain.ExecutionEvent e) {
            return new ExecutionEventResponse(ExecutionEvent.of(e));
        }
    }

    public record ExecutionEvent(
            UUID id,
            UUID organizationId,
            UUID workflowId,
            String source,
            String workflowExternalId,
            String executionExternalId,
            String stepExternalId,
            String stepName,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            Integer retryCount,
            Integer inputSize,
            Integer outputSize,
            Integer itemCount,
            Map<String, Object> outputSignature,
            Integer httpStatus,
            String dataCompleteness,
            Integer runIndex,
            String errorType,
            String errorMessage,
            Map<String, Object> metadata,
            Instant createdAt) {

        public static ExecutionEvent of(com.flowops.domain.ExecutionEvent e) {
            return new ExecutionEvent(
                    e.getId(),
                    e.getOrganizationId(),
                    e.getWorkflowId(),
                    e.getSource(),
                    e.getWorkflowExternalId(),
                    e.getExecutionExternalId(),
                    e.getStepExternalId(),
                    e.getStepName(),
                    e.getStatus(),
                    e.getStartedAt(),
                    e.getFinishedAt(),
                    e.getDurationMs(),
                    e.getRetryCount(),
                    e.getInputSize(),
                    e.getOutputSize(),
                    e.getItemCount(),
                    e.getOutputSignature(),
                    e.getHttpStatus(),
                    e.getDataCompleteness(),
                    e.getRunIndex(),
                    e.getErrorType(),
                    e.getErrorMessage(),
                    e.getMetadata(),
                    e.getCreatedAt());
        }
    }

    // =========================================================================
    // Batch ingestion
    // =========================================================================

    /** {@code POST /api/v1/execution-events/batch} */
    public record BatchIngestRequest(List<IngestExecutionEventRequest> events) {
    }

    public record BatchIngestResponse(int accepted, int rejected, List<String> errors) {
    }

    // =========================================================================
    // Query endpoints (for future use)
    // =========================================================================

    /** {@code GET /api/v1/execution-events} */
    public record ExecutionEventsResponse(List<ExecutionEvent> events) {
    }

    /** {@code GET /api/v1/execution-events/{id} */
    public record ExecutionEventDetailResponse(ExecutionEvent event) {
    }
}