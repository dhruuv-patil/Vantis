package com.flowops.billing.dto;

import java.time.Instant;

public record FoundingOfferResponse(
        String code,
        int discountPercentage,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        String message
) {}
