package com.flowops.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An execution event ingested from an external workflow system.
 *
 * <p>Represents a single step/milestone in an external workflow execution.
 * Used for anomaly detection, health scoring, and cross-system observability.
 *
 * <p>Fields mirror common execution observability: source system, workflow/execution/step
 * identifiers, status, timing, sizes, errors, and arbitrary metadata.
 */
@Entity
@Table(name = "execution_events")
public class ExecutionEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    /**
     * The owning Integration, set only for provider-synced rows (V12). Null for
     * internal rows and legacy custom-injected events. Combined with the external
     * ids it forms the idempotency identity {@code (integration_id,
     * workflow_external_id, execution_external_id, step_external_id)}.
     */
    @Column(name = "integration_id", updatable = false)
    private UUID integrationId;

    @Column(name = "source", nullable = false, length = 64, updatable = false)
    private String source; // e.g., "n8n", "zapier", "temporal", "custom"

    @Column(name = "workflow_external_id", nullable = false, length = 256, updatable = false)
    private String workflowExternalId;

    @Column(name = "execution_external_id", nullable = false, length = 256, updatable = false)
    private String executionExternalId;

    @Column(name = "step_external_id", nullable = false, length = 256, updatable = false)
    private String stepExternalId;

    @Column(name = "step_name", length = 256)
    private String stepName;

    @Column(name = "status", nullable = false, length = 32)
    private String status; // RUNNING, SUCCEEDED, FAILED, WAITING, SKIPPED, etc.

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "input_size")
    private Integer inputSize;

    @Column(name = "output_size")
    private Integer outputSize;

    @Column(name = "item_count")
    private Integer itemCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_signature")
    private Map<String, Object> outputSignature;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "data_completeness", length = 64)
    private String dataCompleteness;

    @Column(name = "run_index")
    private Integer runIndex;

    @Column(name = "error_type", length = 64)
    private String errorType;

    @Column(name = "error_message")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionEvent() {
        // JPA
    }

    public static ExecutionEvent create(
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
            String errorType,
            String errorMessage,
            Map<String, Object> metadata,
            UUID integrationId) {

        return create(
                organizationId,
                workflowId,
                source,
                workflowExternalId,
                executionExternalId,
                stepExternalId,
                stepName,
                status,
                startedAt,
                finishedAt,
                durationMs,
                retryCount,
                inputSize,
                outputSize,
                null, // itemCount
                null, // outputSignature
                null, // httpStatus
                null, // dataCompleteness
                0,    // runIndex
                errorType,
                errorMessage,
                metadata,
                integrationId);
    }

    public static ExecutionEvent create(
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
            UUID integrationId) {

        ExecutionEvent event = new ExecutionEvent();
        event.id = UUID.randomUUID();
        event.organizationId = organizationId;
        event.workflowId = workflowId;
        event.integrationId = integrationId;
        event.source = source;
        event.workflowExternalId = workflowExternalId;
        event.executionExternalId = executionExternalId;
        event.stepExternalId = stepExternalId;
        event.stepName = stepName;
        event.status = status;
        event.startedAt = startedAt;
        event.finishedAt = finishedAt;
        event.durationMs = durationMs;
        event.retryCount = retryCount != null ? retryCount : 0;
        event.inputSize = inputSize;
        event.outputSize = outputSize;
        event.itemCount = itemCount;
        event.outputSignature = outputSignature;
        event.httpStatus = httpStatus;
        event.dataCompleteness = dataCompleteness;
        event.runIndex = runIndex != null ? runIndex : 0;
        event.errorType = errorType;
        event.errorMessage = errorMessage;
        event.metadata = metadata;
        event.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return event;
    }

    // ---- getters ----------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getIntegrationId() {
        return integrationId;
    }

    public String getSource() {
        return source;
    }

    public String getWorkflowExternalId() {
        return workflowExternalId;
    }

    public String getExecutionExternalId() {
        return executionExternalId;
    }

    public String getStepExternalId() {
        return stepExternalId;
    }

    public String getStepName() {
        return stepName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public Integer getInputSize() {
        return inputSize;
    }

    public Integer getOutputSize() {
        return outputSize;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public Map<String, Object> getOutputSignature() {
        return outputSignature;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getDataCompleteness() {
        return dataCompleteness;
    }

    public Integer getRunIndex() {
        return runIndex;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}