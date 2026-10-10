package com.flowops.billing.dto;

public record CancelSubscriptionResponse(
        String status,
        boolean cancelAtPeriodEnd,
        String message
) {}
