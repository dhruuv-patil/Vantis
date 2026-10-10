package com.flowops.billing.dto;

public record ChangePlanResponse(
        String plan,
        String status,
        String interval,
        boolean success,
        String message
) {}
