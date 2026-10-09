package com.flowops.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One normalized reliability observation for an executed node (reliability pivot).
 *
 * <p>Derived at workflow terminal state from the existing {@code execution_nodes} /
 * {@code workflow_executions} rows by the telemetry collector — no hot-path
 * instrumentation. It stores <em>sizes and structure, never values</em>:
 * {@code inputSize}/{@code outputSize} are byte counts and {@code outputSignature} is
 * a field-name {@code ->} type map, so no raw payload, header, or credential is ever
 * persisted here.
 */
@Entity
@Table(name = "node_execution_metrics")
public class NodeExecutionMetric {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version", nullable = false, updatable = false)
    private int workflowVersion;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false, updatable = false, length = 128)
    private String nodeId;

    @Column(name = "node_type", nullable = false, updatable = false, length = 64)
    private String nodeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NodeRunStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "input_size")
    private Integer inputSize;

    @Column(name = "output_size")
    private Integer outputSize;

    @Column(name = "error_type", length = 64)
    private String errorType;

    @Column(name = "error_message")
    private String errorMessage;

    // ---- optional AI/provider fields (null unless the executor reports them) ----

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_size")
    private Integer responseSize;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model_name", length = 80)
    private String modelName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_signature")
    private Map<String, Object> outputSignature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NodeExecutionMetric() {
        // JPA
    }

    public static NodeExecutionMetric create(
            UUID organizationId,
            UUID workflowId,
            int workflowVersion,
            UUID executionId,
            String nodeId,
            String nodeType,
            NodeRunStatus status) {
        NodeExecutionMetric m = new NodeExecutionMetric();
        m.id = UUID.randomUUID();
        m.organizationId = organizationId;
        m.workflowId = workflowId;
        m.workflowVersion = workflowVersion;
        m.executionId = executionId;
        m.nodeId = nodeId;
        m.nodeType = nodeType;
        m.status = status;
        Instant now = now();
        m.createdAt = now;
        m.updatedAt = now;
        return m;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private void touch() {
        this.updatedAt = now();
    }

    public void recordTiming(Instant startedAt, Instant completedAt, Long durationMs) {
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        touch();
    }

    public void recordSizes(Integer inputSize, Integer outputSize) {
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        touch();
    }

    public void recordError(String errorType, String errorMessage) {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        touch();
    }

    public void recordSignature(Map<String, Object> outputSignature) {
        this.outputSignature = outputSignature;
        touch();
    }

    public void recordHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
        touch();
    }

    public void recordRetryCount(int retryCount) {
        this.retryCount = retryCount;
        touch();
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

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public NodeRunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Integer getInputSize() {
        return inputSize;
    }

    public Integer getOutputSize() {
        return outputSize;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getResponseSize() {
        return responseSize;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public Map<String, Object> getOutputSignature() {
        return outputSignature;
    }

    public Integer getItemCount() {
        if (outputSignature != null && outputSignature.get("itemCount") instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
