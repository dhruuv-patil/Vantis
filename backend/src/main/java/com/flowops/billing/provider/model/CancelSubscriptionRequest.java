package com.flowops.billing.provider.model;

import java.util.UUID;

public record CancelSubscriptionRequest(
        UUID organizationId,
        String subscriptionId,
        boolean atPeriodEnd
) {}
