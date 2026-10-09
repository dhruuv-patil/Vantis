package com.flowops.billing.model;

import java.util.Set;

/**
 * Authoritative entitlements quota limits consumed by the backend.
 * All limits defined in PlanCatalog.
 */
public record PlanLimits(
        int activeWorkflows,
        int executionsPerMonth,
        int aiGenerations,
        int aiInvestigationsMonthly,
        int aiInvestigationsLifetimeTrial,
        int seats,
        int workspaces,
        int historyRetentionDays,
        int workflowVersionsKept,
        Set<String> allowedChannels,
        String maxAnomalyMode) {

    // Compatibility accessors for legacy callers
    public int workflows() {
        return activeWorkflows;
    }

    public int aiGenerationsPerMonth() {
        return aiGenerations;
    }

    public int aiInvestigationsPerMonth() {
        return aiInvestigationsMonthly;
    }

    public int teamMembers() {
        return seats;
    }
}
