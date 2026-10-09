package com.flowops.billing.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured response confirming a platform administrator billing override.
 */
public record AdminBillingOverrideResponse(
        UUID organizationId,
        String previousPlan,
        String newPlan,
        String previousStatus,
        String newStatus,
        boolean cancelAtPeriodEnd,
        String reason,
        UUID overriddenBy,
        Instant overriddenAt
) {}
