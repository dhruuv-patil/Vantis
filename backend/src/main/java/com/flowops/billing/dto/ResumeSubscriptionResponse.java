package com.flowops.billing.dto;

public record ResumeSubscriptionResponse(
        String status,
        boolean cancelAtPeriodEnd,
        String message
) {}
