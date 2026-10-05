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
 * Tracks delivery status and retries for outbound alerts (Slack, Email, PagerDuty, Webhooks).
 * Ensures alert delivery is observable and resilient against delivery outages and alert storms.
 */
@Entity
@Table(name = "alert_deliveries")
public class AlertDelivery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel; // WEBHOOK | SLACK | EMAIL | PAGERDUTY

    @Column(name = "destination", nullable = false, length = 256)
    private String destination;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "status", nullable = false, length = 16)
    private String status; // PENDING | DELIVERED | FAILED

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private String error;

    @Column(name = "dedup_key", length = 256)
    private String dedupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private JsonNode payload;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertDelivery() {
        // JPA
    }

    public static AlertDelivery create(
            UUID organizationId,
            UUID incidentId,
            String channel,
            String destination,
            String eventType,
            String dedupKey,
            JsonNode payload) {
        AlertDelivery delivery = new AlertDelivery();
        delivery.id = UUID.randomUUID();
        delivery.organizationId = organizationId;
        delivery.incidentId = incidentId;
        delivery.channel = channel;
        delivery.destination = destination;
        delivery.eventType = eventType;
        delivery.dedupKey = dedupKey;
        delivery.payload = payload;
        delivery.status = "PENDING";
        delivery.attempts = 0;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        delivery.createdAt = now;
        delivery.updatedAt = now;
        return delivery;
    }

    public void markDelivered() {
        this.status = "DELIVERED";
        this.attempts++;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.deliveredAt = now;
        this.updatedAt = now;
        this.error = null;
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.attempts++;
        this.error = error;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getChannel() {
        return channel;
    }

    public String getDestination() {
        return destination;
    }

    public String getEventType() {
        return eventType;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getError() {
        return error;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
