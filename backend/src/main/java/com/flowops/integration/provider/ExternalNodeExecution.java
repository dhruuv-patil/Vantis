package com.flowops.integration.provider;

import java.time.Instant;
import java.util.Map;

/**
 * Normalized representation of a remote step/node execution within a run.
 * Provider-neutral — no n8n/Make/Zapier specifics leak past the provider boundary.
 *
 * <p><strong>Security:</strong> Only sizes, item counts, and structural signatures are stored —
 * never raw inputs, outputs, headers, or credentials. This record is safe to persist in
 * telemetry and pass to the AI evidence builder.
 */
public record ExternalNodeExecution(
        String externalId,
        String executionExternalId,
        String workflowExternalId,
        String name,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long inputSize,
        Long outputSize,
        Integer itemCount,
        Map<String, Object> outputSignature,
        Integer httpStatus,
        String errorMessage,
        String errorCode,
        String dataCompleteness,
        Integer runIndex,
        Map<String, Object> metadata
) {
    public ExternalNodeExecution {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId cannot be blank");
        }
        if (executionExternalId == null || executionExternalId.isBlank()) {
            throw new IllegalArgumentException("executionExternalId cannot be blank");
        }
        if (workflowExternalId == null || workflowExternalId.isBlank()) {
            throw new IllegalArgumentException("workflowExternalId cannot be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
    }

    /**
     * Backwards-compatible 10-argument constructor for legacy callers.
     */
    public ExternalNodeExecution(
            String externalId,
            String executionExternalId,
            String workflowExternalId,
            String name,
            ExecutionStatus status,
            Instant startedAt,
            Instant finishedAt,
            Long inputSize,
            Long outputSize,
            Map<String, Object> metadata) {
        this(externalId, executionExternalId, workflowExternalId, name, status,
                startedAt, finishedAt, inputSize, outputSize, null, null, null, null, null, "COMPLETE", 0, metadata);
    }
}