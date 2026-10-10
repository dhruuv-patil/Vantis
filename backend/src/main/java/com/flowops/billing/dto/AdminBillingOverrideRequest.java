package com.flowops.billing.dto;

import java.util.UUID;

/**
 * Request payload for platform administrator billing override.
 */
public record AdminBillingOverrideRequest(
        UUID organizationId,
        String plan,
        String status,
        Boolean cancelAtPeriodEnd,
        String reason
) {}
