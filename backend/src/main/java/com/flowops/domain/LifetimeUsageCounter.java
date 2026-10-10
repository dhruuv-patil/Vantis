package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lifetime_usage_counters")
public class LifetimeUsageCounter {

    @Id
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "ai_investigations_trial_used", nullable = false)
    private int aiInvestigationsTrialUsed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LifetimeUsageCounter() {
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public int getAiInvestigationsTrialUsed() {
        return aiInvestigationsTrialUsed;
    }
}
