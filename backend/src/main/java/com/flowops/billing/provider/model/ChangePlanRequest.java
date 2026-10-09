package com.flowops.billing.provider.model;

import java.util.UUID;

public record ChangePlanRequest(
        UUID organizationId,
        String subscriptionId,
        String planId,
        String interval,
        String currency
) {}
