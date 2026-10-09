package com.flowops.billing.provider.model;

import java.time.Instant;
import java.util.Map;

public record WebhookEventResult(
        String eventId,
        String eventType,
        Instant timestamp,
        String providerSubscriptionId,
        String providerCustomerId,
        String status,
        String planId,
        Map<String, Object> metadata
) {}
