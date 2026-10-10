package com.flowops.billing.dto;

public record CheckoutRequest(
        String plan,
        String interval,
        String currency,
        String successUrl,
        String cancelUrl
) {}
