package com.flowops.billing.dto;

import com.flowops.billing.PlanLimits;
import java.util.Map;

/**
 * Plans and quota definitions response.
 */
public record PlansResponse(
        Map<String, PlanLimits> plans,
        boolean isPrivateBeta,
        boolean isBillingEnabled,
        String defaultPlan) {
}
