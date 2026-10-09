package com.flowops.billing.dto;

public record CheckoutResponse(
        String sessionId,
        String checkoutUrl,
        String provider,
        String status,
        String message
) {}
