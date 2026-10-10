package com.flowops.billing.provider.model;

import java.util.UUID;

public record CreateCheckoutSessionRequest(
        UUID organizationId,
        String customerId,
        String planId,
        String interval,
        String currency,
        String successUrl,
        String cancelUrl
) {}
