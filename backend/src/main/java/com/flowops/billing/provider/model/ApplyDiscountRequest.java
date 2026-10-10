package com.flowops.billing.provider.model;

import java.util.UUID;

/**
 * Request to apply a discount or promotional offer to a customer or subscription.
 */
public record ApplyDiscountRequest(
        UUID organizationId,
        String customerId,
        String subscriptionId,
        String couponCode,
        int discountPercent,
        int durationMonths
) {
}
