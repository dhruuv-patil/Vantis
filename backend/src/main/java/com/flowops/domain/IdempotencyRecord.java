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
 * Tracks external side-effect idempotency at node execution level.
 * Prevents retries and replays from blindly duplicating dangerous external operations.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false, updatable = false, length = 128)
    private String nodeId;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "effect_status", nullable = false, length = 32)
    private String effectStatus; // STARTED | COMPLETED | FAILED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload")
    private JsonNode responsePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {
        // JPA
    }

    public static IdempotencyRecord start(
            UUID organizationId,
            UUID executionId,
            String nodeId,
            String idempotencyKey,
            int attempt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = UUID.randomUUID();
        record.organizationId = organizationId;
        record.executionId = executionId;
        record.nodeId = nodeId;
        record.idempotencyKey = idempotencyKey;
        record.attempt = attempt;
        record.effectStatus = "STARTED";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public void complete(JsonNode responsePayload) {
        this.effectStatus = "COMPLETED";
        this.responsePayload = responsePayload;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public void fail(JsonNode errorPayload) {
        this.effectStatus = "FAILED";
        this.responsePayload = errorPayload;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getEffectStatus() {
        return effectStatus;
    }

    public JsonNode getResponsePayload() {
        return responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
