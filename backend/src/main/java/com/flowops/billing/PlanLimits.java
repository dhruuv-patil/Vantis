package com.flowops.billing;

/**
 * Plan quota limits record (legacy package location).
 * Maintained for backwards compatibility; delegates to com.flowops.billing.model.PlanLimits.
 */
public record PlanLimits(
        int workflows,
        int executionsPerMonth,
        int aiGenerationsPerMonth,
        int teamMembers) {

    public static PlanLimits from(com.flowops.billing.model.PlanLimits limits) {
        return new PlanLimits(
                limits.activeWorkflows(),
                limits.executionsPerMonth(),
                limits.aiGenerations(),
                limits.seats());
    }
}
