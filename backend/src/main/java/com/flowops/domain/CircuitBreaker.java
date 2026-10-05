package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Persistent circuit breaker state for external dependencies.
 * Prevents cascading failures when a downstream provider is degraded.
 */
@Entity
@Table(name = "circuit_breakers")
public class CircuitBreaker {

    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HALF_OPEN = "HALF_OPEN";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "target_dependency", nullable = false, length = 128)
    private String targetDependency;

    @Column(name = "state", nullable = false, length = 16)
    private String state; // CLOSED | OPEN | HALF_OPEN

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold;

    @Column(name = "recovery_timeout_ms", nullable = false)
    private long recoveryTimeoutMs;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_state_change_at", nullable = false)
    private Instant lastStateChangeAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CircuitBreaker() {
        // JPA
    }

    public static CircuitBreaker create(
            UUID organizationId,
            String targetDependency,
            int failureThreshold,
            long recoveryTimeoutMs) {
        CircuitBreaker cb = new CircuitBreaker();
        cb.id = UUID.randomUUID();
        cb.organizationId = organizationId;
        cb.targetDependency = targetDependency;
        cb.state = STATE_CLOSED;
        cb.failureCount = 0;
        cb.successCount = 0;
        cb.failureThreshold = Math.max(1, failureThreshold);
        cb.recoveryTimeoutMs = Math.max(1000L, recoveryTimeoutMs);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cb.lastStateChangeAt = now;
        cb.createdAt = now;
        cb.updatedAt = now;
        return cb;
    }

    /**
     * Checks if a request should be allowed through based on current state and recovery timeout.
     */
    public boolean allowRequest() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (STATE_CLOSED.equals(this.state)) {
            return true;
        }
        if (STATE_OPEN.equals(this.state)) {
            if (this.lastStateChangeAt != null
                    && now.toEpochMilli() - this.lastStateChangeAt.toEpochMilli() >= this.recoveryTimeoutMs) {
                // Trip to HALF_OPEN to test recovery
                transitionTo(STATE_HALF_OPEN, now);
                return true;
            }
            return false;
        }
        if (STATE_HALF_OPEN.equals(this.state)) {
            return true;
        }
        return true;
    }

    public void recordSuccess() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (STATE_HALF_OPEN.equals(this.state)) {
            this.successCount++;
            if (this.successCount >= 2) {
                transitionTo(STATE_CLOSED, now);
                this.failureCount = 0;
                this.successCount = 0;
            }
        } else if (STATE_CLOSED.equals(this.state)) {
            this.failureCount = 0;
        }
        this.updatedAt = now;
    }

    public void recordFailure() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastFailureAt = now;
        this.failureCount++;

        if (STATE_HALF_OPEN.equals(this.state)) {
            transitionTo(STATE_OPEN, now);
            this.successCount = 0;
        } else if (STATE_CLOSED.equals(this.state) && this.failureCount >= this.failureThreshold) {
            transitionTo(STATE_OPEN, now);
        }
        this.updatedAt = now;
    }

    private void transitionTo(String newState, Instant timestamp) {
        this.state = newState;
        this.lastStateChangeAt = timestamp;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getTargetDependency() {
        return targetDependency;
    }

    public String getState() {
        return state;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public long getRecoveryTimeoutMs() {
        return recoveryTimeoutMs;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public Instant getLastStateChangeAt() {
        return lastStateChangeAt;
    }

    public void setLastStateChangeAt(Instant lastStateChangeAt) {
        this.lastStateChangeAt = lastStateChangeAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
