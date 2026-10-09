package com.flowops.domain;

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
 * Idempotency key for safe replay of externally-triggered executions.
 *
 * <p>When a caller provides an {@code Idempotency-Key} header with a webhook request,
 * the key (scoped by workflow) is stored with the resulting execution id. If the same
 * key is presented again within the TTL, the existing execution id is returned without
 * creating a duplicate run.
 *
 * <p>This prevents accidental duplicate executions from network retries, duplicate
 * events from external systems, or client-side double-submits.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Composite key: "webhook:{workflowId}:{callerKey}" — scoped to prevent cross-workflow collisions. */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 256, unique = true)
    private String key;

    /** The execution id that was created for this key. */
    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    /** When this key expires and can be reused. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
        // JPA
    }

    public static IdempotencyKey create(
            String key,
            UUID executionId,
            java.time.Duration ttl) {

        IdempotencyKey ik = new IdempotencyKey();
        ik.id = UUID.randomUUID();
        ik.key = key;
        ik.executionId = executionId;
        ik.expiresAt = Instant.now()
                .plus(ttl)
                .truncatedTo(ChronoUnit.MILLIS);
        ik.createdAt = Instant.now()
                .truncatedTo(ChronoUnit.MILLIS);

        return ik;
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}