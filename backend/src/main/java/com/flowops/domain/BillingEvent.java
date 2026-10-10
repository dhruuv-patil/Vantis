package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Audit and idempotency log for incoming provider billing events (webhooks).
 * Unique constraint on (provider, provider_event_id) guarantees exactly-once processing.
 */
@Entity
@Table(name = "billing_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_billing_events_provider_event", columnNames = {"provider", "provider_event_id"})
})
public class BillingEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, PROCESSED, FAILED, IGNORED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillingEvent() {
        // JPA
    }

    public static BillingEvent create(
            String provider,
            String providerEventId,
            String eventType,
            Instant eventTimestamp,
            String payload,
            UUID organizationId) {
        BillingEvent event = new BillingEvent();
        event.id = UUID.randomUUID();
        event.provider = provider;
        event.providerEventId = providerEventId;
        event.eventType = eventType;
        event.eventTimestamp = eventTimestamp;
        event.payload = payload;
        event.organizationId = organizationId;
        event.status = "PENDING";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        event.createdAt = now;
        event.updatedAt = now;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
