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
 * Immutable billing audit log tracking plan changes, cancellations, and discounts.
 */
@Entity
@Table(name = "billing_audit_logs")
public class BillingAuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "from_plan", length = 32, updatable = false)
    private String fromPlan;

    @Column(name = "to_plan", length = 32, updatable = false)
    private String toPlan;

    @Column(name = "reason", updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private JsonNode metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BillingAuditLog() {
        // JPA
    }

    public static BillingAuditLog create(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String fromPlan,
            String toPlan,
            String reason,
            JsonNode metadata) {
        BillingAuditLog log = new BillingAuditLog();
        log.id = UUID.randomUUID();
        log.organizationId = organizationId;
        log.actorUserId = actorUserId;
        log.action = action;
        log.fromPlan = fromPlan;
        log.toPlan = toPlan;
        log.reason = reason;
        log.metadata = metadata;
        log.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return log;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getFromPlan() {
        return fromPlan;
    }

    public String getToPlan() {
        return toPlan;
    }

    public String getReason() {
        return reason;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
