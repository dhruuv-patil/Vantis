package com.flowops.billing.provider.model;

import java.util.UUID;

public record CustomerResult(
        String customerId,
        UUID organizationId,
        String email,
        String name,
        String country,
        String currency
) {}
