package com.flowops.billing;

/**
 * Plan quota limits consumed by the backend for authoritative enforcement.
 */
public record PlanLimits(
        int workflows,
        int executionsPerMonth,
        int aiGenerationsPerMonth,
        int teamMembers) {
}
