package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Persisted record of an issued billing notice (e.g. 80% warning or 100% limit reached).
 * Backs exactly-once notification delivery per (organization, period_start, threshold, kind).
 */
@Entity
@Table(
        name = "billing_notices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_billing_notices",
                columnNames = {"organization_id", "period_start", "threshold", "kind"}
        )
)
public class BillingNotice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "threshold", nullable = false, updatable = false)
    private int threshold;

    @Column(name = "kind", nullable = false, updatable = false, length = 64)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BillingNotice() {
        // JPA
    }

    public static BillingNotice create(
            UUID organizationId,
            Instant periodStart,
            int threshold,
            String kind) {
        BillingNotice bn = new BillingNotice();
        bn.id = UUID.randomUUID();
        bn.organizationId = organizationId;
        bn.periodStart = periodStart.truncatedTo(ChronoUnit.MILLIS);
        bn.threshold = threshold;
        bn.kind = kind;
        bn.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return bn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public int getThreshold() {
        return threshold;
    }

    public String getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
