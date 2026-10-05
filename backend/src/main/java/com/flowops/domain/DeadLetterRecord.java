package com.flowops.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable Dead Letter / Failed Work record.
 * Ensures unrecoverable work never silently disappears and can be safely investigated and replayed.
 */
@Entity
@Table(name = "dead_letter_records")
public class DeadLetterRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false, updatable = false)
    private UUID workflowVersionId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "failed_node_id", length = 128)
    private String failedNodeId;

    @Column(name = "failed_node_type", length = 64)
    private String failedNodeType;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_input", nullable = false)
    private JsonNode originalInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_context")
    private JsonNode executionContext;

    @Column(name = "status", nullable = false, length = 32)
    private String status; // NEW | INVESTIGATING | RESOLVED | REPLAYED

    @Column(name = "replayed_execution_id")
    private UUID replayedExecutionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeadLetterRecord() {
        // JPA
    }

    public static DeadLetterRecord create(
            UUID organizationId,
            UUID workflowId,
            UUID workflowVersionId,
            UUID executionId,
            String failedNodeId,
            String failedNodeType,
            int attempts,
            String error,
            JsonNode originalInput,
            JsonNode executionContext) {
        DeadLetterRecord record = new DeadLetterRecord();
        record.id = UUID.randomUUID();
        record.organizationId = organizationId;
        record.workflowId = workflowId;
        record.workflowVersionId = workflowVersionId;
        record.executionId = executionId;
        record.failedNodeId = failedNodeId;
        record.failedNodeType = failedNodeType;
        record.attempts = attempts;
        record.error = error;
        record.originalInput = originalInput;
        record.executionContext = executionContext;
        record.status = "NEW";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public void markInvestigating() {
        this.status = "INVESTIGATING";
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public void markResolved() {
        this.status = "RESOLVED";
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public void markReplayed(UUID newExecutionId) {
        this.status = "REPLAYED";
        this.replayedExecutionId = newExecutionId;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getWorkflowVersionId() {
        return workflowVersionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getFailedNodeId() {
        return failedNodeId;
    }

    public String getFailedNodeType() {
        return failedNodeType;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getError() {
        return error;
    }

    public JsonNode getOriginalInput() {
        return originalInput;
    }

    public JsonNode getExecutionContext() {
        return executionContext;
    }

    public String getStatus() {
        return status;
    }

    public UUID getReplayedExecutionId() {
        return replayedExecutionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
