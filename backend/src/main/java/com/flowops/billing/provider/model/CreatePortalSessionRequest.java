package com.flowops.billing.provider.model;

import java.util.UUID;

public record CreatePortalSessionRequest(
        UUID organizationId,
        String customerId,
        String returnUrl
) {}
