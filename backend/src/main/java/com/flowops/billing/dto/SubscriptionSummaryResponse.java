package com.flowops.billing.dto;

import com.flowops.billing.model.PlanLimits;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubscriptionSummaryResponse(
        UUID organizationId,
        String plan,
        String billingStatus,
        String provider,
        String subscriptionStatus,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean isPrivateBeta,
        Instant betaEndsAt,
        boolean cancelAtPeriodEnd,
        boolean renewalActive,
        boolean foundingOfferEligible,
        boolean foundingOfferApplied,
        Instant foundingOfferEligibleUntil,
        SubscriptionUsageSummary usage,
        PlanLimits planLimits,
        RemainingUsage remainingUsage,
        List<String> availablePlanTransitions,
        boolean billingEnabled
) {
    public record SubscriptionUsageSummary(
            long workflows,
            long executions,
            long aiGenerations,
            long aiInvestigationsMonthly,
            long aiInvestigationsTrial,
            long seats,
            long workspaces
    ) {}

    public record RemainingUsage(
            long workflows,
            long executions,
            long aiGenerations,
            long aiInvestigations,
            long seats,
            long workspaces
    ) {}
}
