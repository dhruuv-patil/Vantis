package com.flowops.billing.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single backend source of truth for plan definitions and quotas.
 * Notice: PlanCatalog.get returns Optional. It NEVER falls back to FREE
 * for unknown/null IDs. Missing/unknown plans cause entitlement checks
 * to fail open (skipCheck).
 */
public final class PlanCatalog {

    public static final int FREE_ACTIVE_WORKFLOWS = 3;
    public static final int FREE_EXECUTIONS_PER_MONTH = 1000;
    public static final int FREE_AI_GENERATIONS = 5;
    public static final int FREE_AI_INVESTIGATIONS_MONTHLY = 0;
    public static final int FREE_AI_INVESTIGATIONS_LIFETIME_TRIAL = 2;
    public static final int FREE_SEATS = 1;
    public static final int FREE_WORKSPACES = 1;
    public static final int FREE_HISTORY_RETENTION_DAYS = 3;
    public static final int FREE_WORKFLOW_VERSIONS_KEPT = 3;
    public static final String FREE_ANOMALY_MODE = "basic";

    public static final int BETA_ACTIVE_WORKFLOWS = 10;
    public static final int BETA_DURATION_DAYS = 45;
    public static final String BETA_ANOMALY_MODE = "full";

    public static final int PRO_ACTIVE_WORKFLOWS = 100;
    public static final int PRO_EXECUTIONS_PER_MONTH = 50_000;
    public static final int PRO_AI_GENERATIONS = 500;
    public static final int PRO_AI_INVESTIGATIONS_MONTHLY = 50;
    public static final int PRO_AI_INVESTIGATIONS_LIFETIME_TRIAL = 0;
    public static final int PRO_SEATS = 10;
    public static final int PRO_WORKSPACES = 5;
    public static final int PRO_HISTORY_RETENTION_DAYS = 30;
    public static final int PRO_WORKFLOW_VERSIONS_KEPT = 30;
    public static final String PRO_ANOMALY_MODE = "full";

    public static final int BUSINESS_ACTIVE_WORKFLOWS = 1000;
    public static final int BUSINESS_EXECUTIONS_PER_MONTH = 1_000_000;
    public static final int BUSINESS_AI_GENERATIONS = 10_000;
    public static final int BUSINESS_AI_INVESTIGATIONS_MONTHLY = 1000;
    public static final int BUSINESS_AI_INVESTIGATIONS_LIFETIME_TRIAL = 0;
    public static final int BUSINESS_SEATS = 100;
    public static final int BUSINESS_WORKSPACES = 50;
    public static final int BUSINESS_HISTORY_RETENTION_DAYS = 90;
    public static final int BUSINESS_WORKFLOW_VERSIONS_KEPT = 100;
    public static final String BUSINESS_ANOMALY_MODE = "tunable";

    public static final Set<String> FREE_CHANNELS = Collections.unmodifiableSet(
            Set.of("EMAIL", "IN_APP", "WEBHOOK"));

    public static final Set<String> PRO_CHANNELS = Collections.unmodifiableSet(
            Set.of("EMAIL", "IN_APP", "WEBHOOK", "SLACK", "DISCORD", "TEAMS", "TELEGRAM", "TWILIO", "PAGERDUTY"));

    public static final Set<String> BUSINESS_CHANNELS = Collections.unmodifiableSet(
            Set.of("EMAIL", "IN_APP", "WEBHOOK", "SLACK", "DISCORD", "TEAMS", "TELEGRAM", "TWILIO", "PAGERDUTY", "CUSTOM"));

    private static final Map<String, PlanDefinition> PLANS;

    static {
        Map<String, PlanDefinition> map = new LinkedHashMap<>();

        // FREE: activeWorkflows 3, executionsPerMonth 1000, aiGenerations 5, aiInvestigationsLifetimeTrial 2 (monthly 0), seats 1, workspaces 1, historyRetentionDays 3, workflowVersionsKept 3
        map.put("FREE", new PlanDefinition(
                "FREE",
                "Free",
                new PlanLimits(
                        FREE_ACTIVE_WORKFLOWS,
                        FREE_EXECUTIONS_PER_MONTH,
                        FREE_AI_GENERATIONS,
                        FREE_AI_INVESTIGATIONS_MONTHLY,
                        FREE_AI_INVESTIGATIONS_LIFETIME_TRIAL,
                        FREE_SEATS,
                        FREE_WORKSPACES,
                        FREE_HISTORY_RETENTION_DAYS,
                        FREE_WORKFLOW_VERSIONS_KEPT,
                        FREE_CHANNELS,
                        FREE_ANOMALY_MODE)));

        // PRIVATE_BETA: Behaves as PRO with activeWorkflows capped at 10, betaStartedAt, betaEndsAt (45 days)
        map.put("PRIVATE_BETA", new PlanDefinition(
                "PRIVATE_BETA",
                "Private Beta",
                new PlanLimits(
                        BETA_ACTIVE_WORKFLOWS,
                        PRO_EXECUTIONS_PER_MONTH,
                        PRO_AI_GENERATIONS,
                        PRO_AI_INVESTIGATIONS_MONTHLY,
                        PRO_AI_INVESTIGATIONS_LIFETIME_TRIAL,
                        PRO_SEATS,
                        PRO_WORKSPACES,
                        PRO_HISTORY_RETENTION_DAYS,
                        PRO_WORKFLOW_VERSIONS_KEPT,
                        PRO_CHANNELS,
                        BETA_ANOMALY_MODE)));

        // PRO: activeWorkflows 100, executionsPerMonth 50,000, aiGenerations 500, aiInvestigationsMonthly 50, seats 10, workspaces 5, historyRetentionDays 30, workflowVersionsKept 30
        map.put("PRO", new PlanDefinition(
                "PRO",
                "Pro",
                new PlanLimits(
                        PRO_ACTIVE_WORKFLOWS,
                        PRO_EXECUTIONS_PER_MONTH,
                        PRO_AI_GENERATIONS,
                        PRO_AI_INVESTIGATIONS_MONTHLY,
                        PRO_AI_INVESTIGATIONS_LIFETIME_TRIAL,
                        PRO_SEATS,
                        PRO_WORKSPACES,
                        PRO_HISTORY_RETENTION_DAYS,
                        PRO_WORKFLOW_VERSIONS_KEPT,
                        PRO_CHANNELS,
                        PRO_ANOMALY_MODE)));

        // BUSINESS: activeWorkflows 1000, executionsPerMonth 1,000,000, aiGenerations 10,000, aiInvestigationsMonthly 1000, seats 100, workspaces 50, historyRetentionDays 90, workflowVersionsKept 100
        map.put("BUSINESS", new PlanDefinition(
                "BUSINESS",
                "Business",
                new PlanLimits(
                        BUSINESS_ACTIVE_WORKFLOWS,
                        BUSINESS_EXECUTIONS_PER_MONTH,
                        BUSINESS_AI_GENERATIONS,
                        BUSINESS_AI_INVESTIGATIONS_MONTHLY,
                        BUSINESS_AI_INVESTIGATIONS_LIFETIME_TRIAL,
                        BUSINESS_SEATS,
                        BUSINESS_WORKSPACES,
                        BUSINESS_HISTORY_RETENTION_DAYS,
                        BUSINESS_WORKFLOW_VERSIONS_KEPT,
                        BUSINESS_CHANNELS,
                        BUSINESS_ANOMALY_MODE)));

        PLANS = Collections.unmodifiableMap(map);
    }

    private PlanCatalog() {
    }

    public static Optional<PlanDefinition> get(String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLANS.get(planId.trim().toUpperCase()));
    }

    public static Map<String, PlanDefinition> getAll() {
        return PLANS;
    }

    public static String suggestedUpgrade(String planId) {
        if (planId == null || "FREE".equalsIgnoreCase(planId) || "PRIVATE_BETA".equalsIgnoreCase(planId)) {
            return "PRO";
        }
        if ("PRO".equalsIgnoreCase(planId)) {
            return "BUSINESS";
        }
        return "BUSINESS";
    }
}
