package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Tracks successful, quota-consuming AI generation and investigation operations.
 */
@Entity
@Table(name = "ai_usage_events")
public class AiUsageEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "metadata")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiUsageEvent() {
        // JPA
    }

    public static AiUsageEvent create(
            UUID organizationId,
            String eventType,
            int quantity,
            String metadata) {
        AiUsageEvent event = new AiUsageEvent();
        event.id = UUID.randomUUID();
        event.organizationId = organizationId;
        event.eventType = eventType;
        event.quantity = quantity;
        event.metadata = metadata;
        event.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return event;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
