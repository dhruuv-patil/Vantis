package com.flowops.billing.provider.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of applying a discount through the billing provider.
 */
public record DiscountResult(
        String discountId,
        UUID organizationId,
        String couponCode,
        int discountPercent,
        int durationMonths,
        Instant appliedAt,
        String status
) {
}
