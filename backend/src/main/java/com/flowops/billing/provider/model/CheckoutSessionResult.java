package com.flowops.billing.provider.model;

public record CheckoutSessionResult(
        String sessionId,
        String url,
        String currency,
        String status,
        String message
) {}
