package com.flowops.billing.dto;

public record ChangePlanRequestDto(
        String plan,
        String interval
) {}
