package com.flowops.billing;

import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AiUsageEvent;
import com.flowops.domain.Subscription;
import com.flowops.repository.AiUsageEventRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core billing and resource usage service.
 * Performs authoritative database calculations for executions, AI generations,
 * active workflows, and team members for the currently authenticated workspace.
 * 
 * Strict multi-tenant isolation: every query is scoped by the caller's organizationId.
 */
@Service
public class BillingService {

    private final SubscriptionRepository subscriptionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowRepository workflowRepository;
    private final OrganizationMemberRepository memberRepository;
    private final AiUsageEventRepository aiUsageEventRepository;
    private final PlanConfig planConfig;

    public BillingService(
            SubscriptionRepository subscriptionRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            OrganizationMemberRepository memberRepository,
            AiUsageEventRepository aiUsageEventRepository,
            PlanConfig planConfig) {
        this.subscriptionRepository = subscriptionRepository;
        this.executionRepository = executionRepository;
        this.workflowRepository = workflowRepository;
        this.memberRepository = memberRepository;
        this.aiUsageEventRepository = aiUsageEventRepository;
        this.planConfig = planConfig;
    }

    /**
     * Resolves the workspace's authoritative subscription or initializes a default
     * record for the current environment without charging or data mutation.
     */
    @Transactional
    public Subscription getOrCreateSubscription(UUID workspaceId) {
        return subscriptionRepository.findByWorkspaceId(workspaceId)
                .orElseGet(() -> {
                    BillingPeriod period = BillingPeriod.currentMonth();
                    String plan = planConfig.defaultPlan();
                    String country = planConfig.defaultCountry();
                    String currency = planConfig.defaultCurrency();
                    Subscription newSub = Subscription.create(
                            workspaceId,
                            plan,
                            "ACTIVE",
                            currency,
                            country,
                            "MONTHLY",
                            period.start(),
                            period.end());
                    return subscriptionRepository.save(newSub);
                });
    }

    /**
     * Returns true billing resource usage for the currently authenticated workspace
     * during its active billing cycle.
     * 
     * NO FAKE DATA: Every counter reflects true database records.
     */
    @Transactional(readOnly = true)
    public WorkspaceUsageResponse getUsage(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        BillingPeriod period = resolveCurrentPeriod(sub);

        // 1. Executions: Count runs created by this workspace in the current period
        long executionsUsed = executionRepository.countByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());

        // 2. AI Generations: Count successful AI events for this workspace in the period
        long aiGenerationsUsed = aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());

        // 3. Active Workflows: Non-archived workflows (DRAFT and PUBLISHED)
        long activeWorkflows = workflowRepository.countActiveByOrganizationId(workspaceId);

        // 4. Team Members: Workspace members in the RBAC table
        long teamMembers = memberRepository.countByOrganizationId(workspaceId);

        PlanLimits limits = planConfig.getLimits(sub.getPlan());

        return new WorkspaceUsageResponse(
                new WorkspaceUsageResponse.PeriodDto(period.start(), period.end()),
                sub.getPlan(),
                new WorkspaceUsageResponse.MetricDto(executionsUsed, limits.executionsPerMonth()),
                new WorkspaceUsageResponse.MetricDto(aiGenerationsUsed, limits.aiGenerationsPerMonth()),
                new WorkspaceUsageResponse.MetricDto(activeWorkflows, limits.workflows()),
                new WorkspaceUsageResponse.MetricDto(teamMembers, limits.teamMembers()));
    }

    /**
     * Resolves full billing overview including subscription metadata, localized currency,
     * and live usage metrics.
     */
    @Transactional(readOnly = true)
    public BillingOverviewResponse getBillingOverview(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        WorkspaceUsageResponse usage = getUsage(workspaceId);

        String currencyCode = sub.getCurrency();
        String symbol = deriveSymbol(currencyCode);

        return new BillingOverviewResponse(
                sub.getPlan(),
                sub.getStatus(),
                sub.getBillingCountry(),
                currencyCode,
                symbol,
                "None — Private Beta",
                planConfig.isPrivateBeta(),
                planConfig.isBillingEnabled(),
                "Private Beta — Pro features are currently free.",
                usage);
    }

    /**
     * Authoritative backend enforcement before starting an execution.
     */
    @Transactional(readOnly = true)
    public void assertCanExecute(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        BillingPeriod period = resolveCurrentPeriod(sub);
        long executionsUsed = executionRepository.countByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());
        PlanLimits limits = planConfig.getLimits(sub.getPlan());

        if (executionsUsed >= limits.executionsPerMonth()) {
            throw new ApiException(
                    ErrorCode.PLAN_LIMIT_EXCEEDED,
                    "Execution limit reached for this billing period ("
                            + executionsUsed + " / " + limits.executionsPerMonth() + ").");
        }
    }

    /**
     * Authoritative backend enforcement before performing an AI workflow generation.
     */
    @Transactional(readOnly = true)
    public void assertCanGenerateAi(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        BillingPeriod period = resolveCurrentPeriod(sub);
        long aiUsed = aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());
        PlanLimits limits = planConfig.getLimits(sub.getPlan());

        if (aiUsed >= limits.aiGenerationsPerMonth()) {
            throw new ApiException(
                    ErrorCode.PLAN_LIMIT_EXCEEDED,
                    "Monthly AI generation limit reached for your plan ("
                            + aiUsed + " / " + limits.aiGenerationsPerMonth() + ").");
        }
    }

    /**
     * Authoritative backend enforcement before creating a new workflow.
     */
    @Transactional(readOnly = true)
    public void assertCanCreateWorkflow(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        long activeWorkflows = workflowRepository.countActiveByOrganizationId(workspaceId);
        PlanLimits limits = planConfig.getLimits(sub.getPlan());

        if (activeWorkflows >= limits.workflows()) {
            throw new ApiException(
                    ErrorCode.PLAN_LIMIT_EXCEEDED,
                    "Active workflow limit reached for your plan ("
                            + activeWorkflows + " / " + limits.workflows() + ").");
        }
    }

    /**
     * Records a billable/usage-consuming AI operation upon successful completion.
     */
    @Transactional
    public void recordAiUsage(
            UUID workspaceId,
            String eventType,
            int quantity,
            String metadata) {
        AiUsageEvent event = AiUsageEvent.create(workspaceId, eventType, quantity, metadata);
        aiUsageEventRepository.save(event);
    }

    private BillingPeriod resolveCurrentPeriod(Subscription sub) {
        Instant now = Instant.now();
        Instant start = sub.getCurrentPeriodStart();
        Instant end = sub.getCurrentPeriodEnd();

        // If the stored period has elapsed, roll over to the current calendar month
        if (now.isAfter(end)) {
            BillingPeriod current = BillingPeriod.currentMonth();
            sub.setCurrentPeriodStart(current.start());
            sub.setCurrentPeriodEnd(current.end());
            subscriptionRepository.save(sub);
            return current;
        }

        return new BillingPeriod(start, end);
    }

    private String deriveSymbol(String currencyCode) {
        try {
            if ("INR".equalsIgnoreCase(currencyCode)) return "₹";
            if ("USD".equalsIgnoreCase(currencyCode)) return "$";
            if ("GBP".equalsIgnoreCase(currencyCode)) return "£";
            if ("EUR".equalsIgnoreCase(currencyCode)) return "€";
            if ("JPY".equalsIgnoreCase(currencyCode)) return "¥";
            Currency cur = Currency.getInstance(currencyCode);
            return cur.getSymbol(Locale.US);
        } catch (Exception e) {
            return "$";
        }
    }
}
