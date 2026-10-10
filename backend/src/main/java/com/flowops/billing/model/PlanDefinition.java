package com.flowops.billing.model;

/**
 * Metadata and quota limits for a SaaS plan.
 */
public record PlanDefinition(
        String id,
        String name,
        PlanLimits limits) {
}
