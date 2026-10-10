package com.flowops.billing.provider.model;

import java.util.UUID;

public record ResumeSubscriptionRequest(
        UUID organizationId,
        String subscriptionId
) {}
