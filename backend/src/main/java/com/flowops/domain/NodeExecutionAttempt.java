package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Persisted attempt history for every execution step.
 * Allows inspecting attempt 1, attempt 2, attempt 3... with timestamps, duration, and error.
 */
@Entity
@Table(name = "node_execution_attempts")
public class NodeExecutionAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false, updatable = false, length = 128)
    private String nodeId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "status", nullable = false, length = 16)
    private String status; // RUNNING | SUCCEEDED | FAILED | TIMED_OUT

    @Column(name = "error")
    private String error;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NodeExecutionAttempt() {
        // JPA
    }

    public static NodeExecutionAttempt create(
            UUID executionId,
            String nodeId,
            int attemptNumber) {
        NodeExecutionAttempt attempt = new NodeExecutionAttempt();
        attempt.id = UUID.randomUUID();
        attempt.executionId = executionId;
        attempt.nodeId = nodeId;
        attempt.attemptNumber = attemptNumber;
        attempt.status = "RUNNING";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        attempt.startedAt = now;
        attempt.createdAt = now;
        return attempt;
    }

    public void succeed() {
        this.status = "SUCCEEDED";
        this.finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (this.startedAt != null) {
            this.durationMs = this.finishedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        }
    }

    public void fail(String error) {
        this.status = "FAILED";
        this.error = error;
        this.finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (this.startedAt != null) {
            this.durationMs = this.finishedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        }
    }

    public void timeout(String error) {
        this.status = "TIMED_OUT";
        this.error = error;
        this.finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (this.startedAt != null) {
            this.durationMs = this.finishedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
