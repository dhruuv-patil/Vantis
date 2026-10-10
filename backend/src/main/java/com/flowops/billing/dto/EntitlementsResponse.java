package com.flowops.billing.dto;

import java.time.Instant;
import java.util.Set;

public record EntitlementsResponse(
        String plan,
        Instant periodStart,
        Instant periodEnd,
        UsageMetricDto workflows,
        UsageMetricDto executions,
        UsageMetricDto aiGenerations,
        InvestigationMetricDto aiInvestigations,
        UsageMetricDto seats,
        UsageMetricDto workspaces,
        int historyRetentionDays,
        int workflowVersionsKept,
        Set<String> allowedChannels,
        String anomalyMode
) {
    public record UsageMetricDto(
            long used,
            long limit,
            long remaining,
            int percentage
    ) {}

    public record InvestigationMetricDto(
            long monthlyUsed,
            long monthlyLimit,
            long lifetimeTrialUsed,
            long lifetimeTrialLimit,
            long remaining,
            int percentage
    ) {}
}
