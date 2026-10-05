package com.flowops.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run of a workflow, bound to the immutable {@link WorkflowVersion} it started
 * on. Owned by an organization; foreign keys are plain UUIDs so every query stays
 * explicitly org-scoped (contract §2 rule 6).
 *
 * <p>State transitions are expressed as methods rather than a public setter, so an
 * illegal jump (e.g. reopening a terminal run) is impossible to write by accident.
 */
@Entity
@Table(name = "workflow_executions")
public class WorkflowExecution {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false, updatable = false)
    private UUID workflowVersionId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16, updatable = false)
    private TriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_payload", nullable = false, updatable = false)
    private JsonNode triggerPayload;

    @Column(name = "error")
    private String error;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "queue_wait_ms")
    private Long queueWaitMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowExecution() {
        // JPA
    }

    public static WorkflowExecution create(
            UUID organizationId,
            UUID workflowId,
            UUID workflowVersionId,
            int versionNumber,
            TriggerType triggerType,
            JsonNode triggerPayload,
            UUID createdBy) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.id = UUID.randomUUID();
        execution.organizationId = organizationId;
        execution.workflowId = workflowId;
        execution.workflowVersionId = workflowVersionId;
        execution.versionNumber = versionNumber;
        execution.status = ExecutionStatus.QUEUED;
        execution.triggerType = triggerType;
        execution.triggerPayload = triggerPayload;
        execution.createdBy = createdBy;
        Instant now = now();
        execution.createdAt = now;
        execution.updatedAt = now;
        return execution;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private void touch() {
        this.updatedAt = now();
    }

    /** Enter (or re-enter, on resume) the running state. Records the first start. */
    public void markRunning() {
        this.status = ExecutionStatus.RUNNING;
        Instant n = now();
        if (this.startedAt == null) {
            this.startedAt = n;
            if (this.createdAt != null) {
                this.queueWaitMs = Math.max(0L, n.toEpochMilli() - this.createdAt.toEpochMilli());
            }
        }
        this.lastHeartbeatAt = n;
        this.error = null;
        touch();
    }

    public void recordHeartbeat() {
        this.lastHeartbeatAt = now();
        touch();
    }

    public void assignWorker(String workerId) {
        this.workerId = workerId;
        this.lastHeartbeatAt = now();
        touch();
    }

    /** Suspend for a human decision; the worker thread is released. */
    public void markWaiting() {
        this.status = ExecutionStatus.WAITING;
        touch();
    }

    public void markSucceeded() {
        this.status = ExecutionStatus.SUCCEEDED;
        this.finishedAt = now();
        touch();
    }

    public void markFailed(String error) {
        this.status = ExecutionStatus.FAILED;
        this.error = error;
        this.finishedAt = now();
        touch();
    }

    public void markCanceled() {
        this.status = ExecutionStatus.CANCELED;
        this.finishedAt = now();
        touch();
    }

    /** Re-open a failed run so the engine can re-execute the reset nodes. */
    public void reopenForRetry() {
        this.status = ExecutionStatus.QUEUED;
        this.error = null;
        this.finishedAt = null;
        touch();
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

    public int getVersionNumber() {
        return versionNumber;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public JsonNode getTriggerPayload() {
        return triggerPayload;
    }

    public String getError() {
        return error;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Long getQueueWaitMs() {
        return queueWaitMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
