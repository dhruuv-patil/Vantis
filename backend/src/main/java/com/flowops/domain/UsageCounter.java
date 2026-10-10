package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_counters", uniqueConstraints = {
        @UniqueConstraint(name = "uq_usage_counters_org_period", columnNames = {"organization_id", "period_start"})
})
public class UsageCounter {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "executions", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long executions = 0L;

    @Column(name = "ai_generations", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long aiGenerations = 0L;

    @Column(name = "ai_investigations", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long aiInvestigations = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsageCounter() {
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

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public long getExecutions() {
        return executions;
    }

    public long getAiGenerations() {
        return aiGenerations;
    }

    public long getAiInvestigations() {
        return aiInvestigations;
    }
}
