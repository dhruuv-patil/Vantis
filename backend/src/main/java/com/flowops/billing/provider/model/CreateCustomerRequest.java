package com.flowops.billing.provider.model;

import java.util.UUID;

public record CreateCustomerRequest(
        UUID organizationId,
        String email,
        String name,
        String country,
        String currency
) {}
