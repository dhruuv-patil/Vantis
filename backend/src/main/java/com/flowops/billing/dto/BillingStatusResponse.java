package com.flowops.billing.dto;

import java.time.Instant;

public record BillingStatusResponse(
        SubscriptionSummaryResponse subscription,
        EntitlementsResponse entitlements,
        String provider,
        boolean isBillingEnabled,
        boolean isPrivateBeta,
        boolean foundingOfferEligible,
        boolean foundingOfferApplied,
        boolean dunningActive,
        Instant pastDueSince
) {}
