package com.flowops.common.error;

/**
 * Machine-readable details for PLAN_LIMIT_EXCEEDED errors.
 * Frontend UpgradePrompt directly consumes these fields without parsing messages.
 */
public record PlanLimitDetail(
        String limitKey,
        long current,
        long max,
        String planId,
        String suggestedPlan) {
}
