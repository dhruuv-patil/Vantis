package com.flowops.billing.provider.model;

import java.time.Instant;

public record SubscriptionResult(
        String subscriptionId,
        String customerId,
        String planId,
        String status,
        String interval,
        String currency,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {}
