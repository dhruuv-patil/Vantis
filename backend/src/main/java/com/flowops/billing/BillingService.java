package com.flowops.billing;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.billing.dto.AdminBillingOverrideRequest;
import com.flowops.billing.dto.AdminBillingOverrideResponse;
import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.BillingStatusResponse;
import com.flowops.billing.dto.CancelSubscriptionRequestDto;
import com.flowops.billing.dto.CancelSubscriptionResponse;
import com.flowops.billing.dto.ChangePlanRequestDto;
import com.flowops.billing.dto.ChangePlanResponse;
import com.flowops.billing.dto.CheckoutRequest;
import com.flowops.billing.dto.CheckoutResponse;
import com.flowops.billing.dto.EntitlementsResponse;
import com.flowops.billing.dto.FoundingOfferResponse;
import com.flowops.billing.dto.InvoiceResponse;
import com.flowops.billing.dto.PortalRequest;
import com.flowops.billing.dto.PortalResponse;
import com.flowops.billing.dto.ResumeSubscriptionResponse;
import com.flowops.billing.dto.SubscriptionSummaryResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.provider.BillingProvider;
import com.flowops.billing.provider.NoopBillingProvider;
import com.flowops.billing.provider.exception.BillingConfigurationException;
import com.flowops.billing.provider.exception.BillingValidationException;
import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CreatePortalSessionRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.InvoiceResult;
import com.flowops.billing.provider.model.PortalSessionResult;
import com.flowops.billing.provider.model.ResumeSubscriptionRequest;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.razorpay.RazorpayProperties;
import com.flowops.billing.provider.stripe.StripeProperties;
import com.flowops.billing.service.BillingPeriodService;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.SubscriptionLifecycleService;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AiUsageEvent;
import com.flowops.domain.BillingAuditLog;
import com.flowops.domain.BillingDiscount;
import com.flowops.domain.Subscription;
import com.flowops.repository.AiUsageEventRepository;
import com.flowops.repository.BillingAuditLogRepository;
import com.flowops.repository.BillingDiscountRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public static final Set<String> STRIPE_SUPPORTED_CURRENCIES = StripeProperties.SUPPORTED_CURRENCIES;

    private final SubscriptionRepository subscriptionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowRepository workflowRepository;
    private final OrganizationMemberRepository memberRepository;
    private final AiUsageEventRepository aiUsageEventRepository;
    private final PlanConfig planConfig;
    private final EntitlementService entitlementService;
    private final BillingProvider billingProvider;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final BillingDiscountRepository discountRepository;
    private final UsageMeterService usageMeterService;
    private final BillingPeriodService billingPeriodService;
    private final BillingAuditLogRepository auditLogRepository;
    private final StripeProperties stripeProperties;
    private final RazorpayProperties razorpayProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public BillingService(
            SubscriptionRepository subscriptionRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            OrganizationMemberRepository memberRepository,
            AiUsageEventRepository aiUsageEventRepository,
            PlanConfig planConfig,
            @org.springframework.beans.factory.annotation.Autowired(required = false) EntitlementService entitlementService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BillingProvider billingProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SubscriptionLifecycleService subscriptionLifecycleService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BillingDiscountRepository discountRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) UsageMeterService usageMeterService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BillingPeriodService billingPeriodService,
            BillingAuditLogRepository auditLogRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StripeProperties stripeProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RazorpayProperties razorpayProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.executionRepository = executionRepository;
        this.workflowRepository = workflowRepository;
        this.memberRepository = memberRepository;
        this.aiUsageEventRepository = aiUsageEventRepository;
        this.planConfig = planConfig;
        this.entitlementService = entitlementService;
        if (planConfig != null && planConfig.isBillingEnabled()) {
            if (billingProvider == null) {
                throw new BillingConfigurationException(
                        "Billing is enabled (BILLING_ENABLED=true) but no BillingProvider bean was configured or available");
            }
        }
        this.billingProvider = billingProvider != null ? billingProvider : new NoopBillingProvider();
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.discountRepository = discountRepository;
        this.usageMeterService = usageMeterService;
        this.billingPeriodService = billingPeriodService != null ? billingPeriodService : new BillingPeriodService();
        this.auditLogRepository = auditLogRepository;
        this.stripeProperties = stripeProperties;
        this.razorpayProperties = razorpayProperties;
    }

    public BillingService(
            SubscriptionRepository subscriptionRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            OrganizationMemberRepository memberRepository,
            AiUsageEventRepository aiUsageEventRepository,
            PlanConfig planConfig) {
        this(subscriptionRepository, executionRepository, workflowRepository, memberRepository,
                aiUsageEventRepository, planConfig, null, null, null, null, null, null, null, null, null);
    }

    public BillingService(
            SubscriptionRepository subscriptionRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            OrganizationMemberRepository memberRepository,
            AiUsageEventRepository aiUsageEventRepository,
            PlanConfig planConfig,
            EntitlementService entitlementService) {
        this(subscriptionRepository, executionRepository, workflowRepository, memberRepository,
                aiUsageEventRepository, planConfig, entitlementService, null, null, null, null, null, null, null, null);
    }

    public BillingService(
            SubscriptionRepository subscriptionRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            OrganizationMemberRepository memberRepository,
            AiUsageEventRepository aiUsageEventRepository,
            PlanConfig planConfig,
            EntitlementService entitlementService,
            BillingProvider billingProvider,
            SubscriptionLifecycleService subscriptionLifecycleService,
            BillingDiscountRepository discountRepository,
            UsageMeterService usageMeterService,
            BillingPeriodService billingPeriodService) {
        this(subscriptionRepository, executionRepository, workflowRepository, memberRepository,
                aiUsageEventRepository, planConfig, entitlementService, billingProvider,
                subscriptionLifecycleService, discountRepository, usageMeterService, billingPeriodService,
                null, null, null);
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
                    Subscription saved = subscriptionRepository.save(newSub);
                    if (entitlementService != null) {
                        entitlementService.evictPlanCache(workspaceId);
                    }
                    return saved;
                });
    }

    /**
     * Returns true billing resource usage for the currently authenticated workspace
     * during its active billing cycle.
     */
    @Transactional(readOnly = true)
    public WorkspaceUsageResponse getUsage(UUID workspaceId) {
        Subscription sub = getOrCreateSubscription(workspaceId);
        BillingPeriod period = resolveCurrentPeriod(sub);

        long executionsUsed = executionRepository.countByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());
        long aiGenerationsUsed = aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(
                workspaceId, period.start(), period.end());
        long activeWorkflows = workflowRepository.countActiveByOrganizationId(workspaceId);
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
     * 6.1 Subscription summary: returns the organization's complete billing state.
     */
    @Transactional(readOnly = true)
    public SubscriptionSummaryResponse getSubscriptionSummary(UUID organizationId) {
        Subscription sub = getOrCreateSubscription(organizationId);
        BillingPeriod period = billingPeriodService.resolvePeriod(sub);
        com.flowops.billing.model.PlanLimits limits = PlanCatalog.get(sub.getPlan())
                .map(com.flowops.billing.model.PlanDefinition::limits)
                .orElseGet(() -> PlanCatalog.get("FREE").orElseThrow().limits());

        long workflows = workflowRepository.countActiveByOrganizationId(organizationId);
        long executions = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "executions")
                : executionRepository.countByOrganizationIdAndPeriod(organizationId, period.start(), period.end());
        long aiGenerations = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "ai_generations")
                : aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(organizationId, period.start(), period.end());
        long aiInvestigationsMonthly = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "ai_investigations")
                : 0L;
        long aiInvestigationsTrial = usageMeterService != null
                ? usageMeterService.getLifetimeTrialUsage(organizationId)
                : 0L;
        long seats = memberRepository.countByOrganizationId(organizationId);
        long workspaces = 1L;

        SubscriptionSummaryResponse.SubscriptionUsageSummary usageSummary =
                new SubscriptionSummaryResponse.SubscriptionUsageSummary(
                        workflows, executions, aiGenerations, aiInvestigationsMonthly, aiInvestigationsTrial, seats, workspaces);

        long remainingWorkflows = Math.max(0, limits.activeWorkflows() - workflows);
        long remainingExecutions = Math.max(0, limits.executionsPerMonth() - executions);
        long remainingAiGenerations = Math.max(0, limits.aiGenerations() - aiGenerations);
        long remainingAiInvestigations = limits.aiInvestigationsMonthly() > 0
                ? Math.max(0, limits.aiInvestigationsMonthly() - aiInvestigationsMonthly)
                : Math.max(0, limits.aiInvestigationsLifetimeTrial() - aiInvestigationsTrial);
        long remainingSeats = Math.max(0, limits.seats() - seats);
        long remainingWorkspaces = Math.max(0, limits.workspaces() - workspaces);

        SubscriptionSummaryResponse.RemainingUsage remainingUsage =
                new SubscriptionSummaryResponse.RemainingUsage(
                        remainingWorkflows, remainingExecutions, remainingAiGenerations, remainingAiInvestigations, remainingSeats, remainingWorkspaces);

        boolean foundingOfferApplied = discountRepository != null &&
                discountRepository.existsByOrganizationIdAndCode(organizationId, SubscriptionLifecycleService.FOUNDING_OFFER_CODE);
        boolean foundingOfferEligible = sub.getFoundingOfferEligibleUntil() != null
                && Instant.now().isBefore(sub.getFoundingOfferEligibleUntil())
                && !foundingOfferApplied;

        List<String> transitions = resolveAvailablePlanTransitions(sub.getPlan());
        boolean renewalActive = !sub.isCancelAtPeriodEnd() && "ACTIVE".equalsIgnoreCase(sub.getStatus());

        return new SubscriptionSummaryResponse(
                sub.getWorkspaceId(),
                sub.getPlan(),
                sub.getStatus(),
                sub.getProvider(),
                sub.getStatus(),
                period.start(),
                period.end(),
                planConfig.isPrivateBeta(),
                sub.getBetaEndsAt(),
                sub.isCancelAtPeriodEnd(),
                renewalActive,
                foundingOfferEligible,
                foundingOfferApplied,
                sub.getFoundingOfferEligibleUntil(),
                usageSummary,
                limits,
                remainingUsage,
                transitions,
                planConfig.isBillingEnabled());
    }

    public List<String> resolveAvailablePlanTransitions(String currentPlan) {
        if (currentPlan == null || "FREE".equalsIgnoreCase(currentPlan) || "PRIVATE_BETA".equalsIgnoreCase(currentPlan)) {
            return List.of("PRO", "BUSINESS");
        }
        if ("PRO".equalsIgnoreCase(currentPlan)) {
            return List.of("FREE", "BUSINESS");
        }
        if ("BUSINESS".equalsIgnoreCase(currentPlan)) {
            return List.of("FREE", "PRO");
        }
        return List.of("PRO", "BUSINESS");
    }

    /**
     * 6.9 Entitlements endpoint: live usage metrics against PlanCatalog quotas.
     */
    @Transactional(readOnly = true)
    public EntitlementsResponse getEntitlements(UUID organizationId) {
        Subscription sub = getOrCreateSubscription(organizationId);
        BillingPeriod period = billingPeriodService.resolvePeriod(sub);
        com.flowops.billing.model.PlanLimits limits = PlanCatalog.get(sub.getPlan())
                .map(com.flowops.billing.model.PlanDefinition::limits)
                .orElseGet(() -> PlanCatalog.get("FREE").orElseThrow().limits());

        long workflows = workflowRepository.countActiveByOrganizationId(organizationId);
        long executions = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "executions")
                : executionRepository.countByOrganizationIdAndPeriod(organizationId, period.start(), period.end());
        long aiGenerations = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "ai_generations")
                : aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(organizationId, period.start(), period.end());
        long aiInvestigationsMonthly = usageMeterService != null
                ? usageMeterService.getCurrentUsage(organizationId, period.start(), "ai_investigations")
                : 0L;
        long aiInvestigationsTrial = usageMeterService != null
                ? usageMeterService.getLifetimeTrialUsage(organizationId)
                : 0L;
        long seats = memberRepository.countByOrganizationId(organizationId);
        long workspaces = 1L;

        return new EntitlementsResponse(
                sub.getPlan(),
                period.start(),
                period.end(),
                buildUsageMetric(workflows, limits.activeWorkflows()),
                buildUsageMetric(executions, limits.executionsPerMonth()),
                buildUsageMetric(aiGenerations, limits.aiGenerations()),
                buildInvestigationMetric(aiInvestigationsMonthly, limits.aiInvestigationsMonthly(), aiInvestigationsTrial, limits.aiInvestigationsLifetimeTrial()),
                buildUsageMetric(seats, limits.seats()),
                buildUsageMetric(workspaces, limits.workspaces()),
                limits.historyRetentionDays(),
                limits.workflowVersionsKept(),
                limits.allowedChannels(),
                limits.maxAnomalyMode());
    }

    private EntitlementsResponse.UsageMetricDto buildUsageMetric(long used, long limit) {
        long remaining = Math.max(0, limit - used);
        int percentage = limit <= 0 ? 0 : (int) Math.min(100, Math.round(((double) used / limit) * 100));
        return new EntitlementsResponse.UsageMetricDto(used, limit, remaining, percentage);
    }

    private EntitlementsResponse.InvestigationMetricDto buildInvestigationMetric(
            long monthlyUsed, long monthlyLimit, long trialUsed, long trialLimit) {
        long remaining = monthlyLimit > 0
                ? Math.max(0, monthlyLimit - monthlyUsed)
                : Math.max(0, trialLimit - trialUsed);
        long cap = monthlyLimit > 0 ? monthlyLimit : trialLimit;
        long used = monthlyLimit > 0 ? monthlyUsed : trialUsed;
        int percentage = cap <= 0 ? 0 : (int) Math.min(100, Math.round(((double) used / cap) * 100));
        return new EntitlementsResponse.InvestigationMetricDto(
                monthlyUsed, monthlyLimit, trialUsed, trialLimit, remaining, percentage);
    }

    /**
     * 6.10 Billing status endpoint: aggregated billing, subscription, entitlements, and lifecycle state.
     */
    @Transactional(readOnly = true)
    public BillingStatusResponse getBillingStatus(UUID organizationId) {
        SubscriptionSummaryResponse summary = getSubscriptionSummary(organizationId);
        EntitlementsResponse entitlements = getEntitlements(organizationId);
        Subscription sub = getOrCreateSubscription(organizationId);
        boolean dunningActive = sub.getPastDueSince() != null;

        return new BillingStatusResponse(
                summary,
                entitlements,
                billingProvider.getName(),
                planConfig.isBillingEnabled(),
                planConfig.isPrivateBeta(),
                summary.foundingOfferEligible(),
                summary.foundingOfferApplied(),
                dunningActive,
                sub.getPastDueSince());
    }

    /**
     * 6.2 Checkout: creates a checkout session with the active billing provider.
     */
    @Transactional
    public CheckoutResponse createCheckoutSession(UUID organizationId, UUID actorUserId, String userEmail, CheckoutRequest request) {
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        if (request == null || request.plan() == null || request.plan().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Target plan is required.");
        }
        String targetPlan = request.plan().trim().toUpperCase(Locale.ROOT);
        if (!"PRO".equals(targetPlan) && !"BUSINESS".equals(targetPlan)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid checkout plan: " + targetPlan + ". Only PRO and BUSINESS plans can be purchased.");
        }
        Subscription sub = getOrCreateSubscription(organizationId);
        if (targetPlan.equalsIgnoreCase(sub.getPlan()) && "ACTIVE".equalsIgnoreCase(sub.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Organization is already subscribed to " + targetPlan);
        }

        String currency = resolveAndValidateCurrency(request.currency(), sub);
        String interval = resolveAndValidateInterval(request.interval(), sub);

        // Price integrity: ensure configured price ID exists for plan + currency + interval
        validateConfiguredPriceId(targetPlan, currency, interval);

        try {
            String customerId = sub.getProviderCustomerId();
            if (customerId == null || customerId.isBlank()) {
                CustomerResult cust = billingProvider.createCustomer(new CreateCustomerRequest(
                        organizationId,
                        userEmail != null ? userEmail : "billing@" + organizationId + ".flowops.local",
                        "Org " + organizationId,
                        sub.getBillingCountry(),
                        currency));
                customerId = cust.customerId();
                sub.setProviderCustomerId(customerId);
                subscriptionRepository.save(sub);
            }

            CheckoutSessionResult session = billingProvider.createCheckoutSession(new CreateCheckoutSessionRequest(
                    organizationId,
                    customerId,
                    targetPlan,
                    interval,
                    currency,
                    request.successUrl(),
                    request.cancelUrl()));

            // Audit record: checkout initiated (safe metadata, no credentials or secrets)
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("targetPlan", targetPlan);
            meta.put("interval", interval);
            meta.put("currency", currency);
            meta.put("provider", billingProvider.getName());
            meta.put("sessionId", session.sessionId());
            recordAuditLog(organizationId, actorUserId, "CHECKOUT_INITIATED", sub.getPlan(), targetPlan,
                    "Checkout session created for plan " + targetPlan, meta);

            return new CheckoutResponse(
                    session.sessionId(),
                    session.url(),
                    billingProvider.getName(),
                    session.status(),
                    session.message());
        } catch (ApiException e) {
            throw e;
        } catch (BillingValidationException bve) {
            log.warn("Validation error in checkout for org={}: {}", organizationId, bve.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, bve.getMessage());
        } catch (Exception e) {
            log.error("Failed to create checkout session for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to create checkout session: " + e.getMessage());
        }
    }

    public CheckoutResponse createCheckoutSession(UUID organizationId, String userEmail, CheckoutRequest request) {
        return createCheckoutSession(organizationId, null, userEmail, request);
    }

    /**
     * 6.3 Customer portal: creates a self-service management session.
     */
    @Transactional
    public PortalResponse createPortalSession(UUID organizationId, UUID actorUserId, String userEmail, PortalRequest request) {
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        Subscription sub = getOrCreateSubscription(organizationId);
        try {
            String customerId = sub.getProviderCustomerId();
            if (customerId == null || customerId.isBlank()) {
                CustomerResult cust = billingProvider.createCustomer(new CreateCustomerRequest(
                        organizationId,
                        userEmail != null ? userEmail : "billing@" + organizationId + ".flowops.local",
                        "Org " + organizationId,
                        sub.getBillingCountry(),
                        sub.getCurrency()));
                customerId = cust.customerId();
                sub.setProviderCustomerId(customerId);
                subscriptionRepository.save(sub);
            }
            String returnUrl = (request != null && request.returnUrl() != null && !request.returnUrl().isBlank())
                    ? request.returnUrl()
                    : null;
            PortalSessionResult portalSession = billingProvider.createPortalSession(new CreatePortalSessionRequest(
                    organizationId, customerId, returnUrl));

            // Audit record: portal session created
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("customerId", customerId);
            meta.put("provider", billingProvider.getName());
            recordAuditLog(organizationId, actorUserId, "PORTAL_SESSION_CREATED", sub.getPlan(), sub.getPlan(),
                    "Customer portal session created", meta);

            return new PortalResponse(portalSession.portalUrl());
        } catch (ApiException e) {
            throw e;
        } catch (BillingValidationException bve) {
            log.warn("Validation error in portal for org={}: {}", organizationId, bve.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, bve.getMessage());
        } catch (Exception e) {
            log.error("Failed to create portal session for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to create customer portal session: " + e.getMessage());
        }
    }

    public PortalResponse createPortalSession(UUID organizationId, String userEmail, PortalRequest request) {
        return createPortalSession(organizationId, null, userEmail, request);
    }

    /**
     * 6.4 Change plan: modifies the subscription plan via provider.
     */
    @Transactional
    public ChangePlanResponse changePlan(UUID organizationId, UUID actorUserId, ChangePlanRequestDto request) {
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        if (request == null || request.plan() == null || request.plan().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Target plan is required.");
        }
        String targetPlan = request.plan().trim().toUpperCase(Locale.ROOT);
        if (PlanCatalog.get(targetPlan).isEmpty() || "PRIVATE_BETA".equalsIgnoreCase(targetPlan)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid target plan: " + targetPlan);
        }
        Subscription sub = getOrCreateSubscription(organizationId);
        if (targetPlan.equalsIgnoreCase(sub.getPlan())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Subscription is already on " + targetPlan);
        }

        String interval = resolveAndValidateInterval(request.interval(), sub);
        String currency = resolveAndValidateCurrency(null, sub);
        String subId = sub.getProviderSubscriptionId();

        try {
            if ("FREE".equalsIgnoreCase(targetPlan)) {
                if (subId != null && !subId.isBlank()) {
                    billingProvider.cancelSubscription(new CancelSubscriptionRequest(organizationId, subId, true));
                }
                // CRITICAL: Webhook remains authoritative.
                // Do NOT mutate sub.setCancelAtPeriodEnd(true) or save sub locally.
                ObjectNode meta = JsonNodeFactory.instance.objectNode();
                meta.put("targetPlan", "FREE");
                meta.put("interval", interval);
                meta.put("provider", billingProvider.getName());
                recordAuditLog(organizationId, actorUserId, "PLAN_CHANGE_REQUESTED", sub.getPlan(), "FREE",
                        "Downgrade to FREE scheduled at period end", meta);

                return new ChangePlanResponse("FREE", "active", interval, true, "Subscription scheduled for downgrade to FREE at period end.");
            }

            // Price integrity check
            validateConfiguredPriceId(targetPlan, currency, interval);

            SubscriptionResult result = billingProvider.changePlan(new ChangePlanRequest(
                    organizationId,
                    subId != null ? subId : "sub_" + organizationId,
                    targetPlan,
                    interval,
                    currency));

            // CRITICAL: Webhook remains authoritative.
            // Do NOT mutate sub.setPlan(targetPlan) locally!
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("targetPlan", targetPlan);
            meta.put("interval", interval);
            meta.put("currency", currency);
            meta.put("provider", billingProvider.getName());
            recordAuditLog(organizationId, actorUserId, "PLAN_CHANGE_REQUESTED", sub.getPlan(), targetPlan,
                    "Plan change requested to " + targetPlan, meta);

            return new ChangePlanResponse(targetPlan, result.status(), interval, true, "Plan changed to " + targetPlan);
        } catch (ApiException e) {
            throw e;
        } catch (BillingValidationException bve) {
            log.warn("Validation error in change plan for org={}: {}", organizationId, bve.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, bve.getMessage());
        } catch (Exception e) {
            log.error("Failed to change plan for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to change subscription plan: " + e.getMessage());
        }
    }

    public ChangePlanResponse changePlan(UUID organizationId, ChangePlanRequestDto request) {
        return changePlan(organizationId, null, request);
    }

    /**
     * 6.5 Cancel subscription: schedules or immediately cancels subscription with provider.
     */
    @Transactional
    public CancelSubscriptionResponse cancelSubscription(UUID organizationId, UUID actorUserId, CancelSubscriptionRequestDto request) {
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        Subscription sub = getOrCreateSubscription(organizationId);
        if ("FREE".equalsIgnoreCase(sub.getPlan()) || "CANCELED".equalsIgnoreCase(sub.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Subscription is already cancelled or on Free plan.");
        }
        boolean atPeriodEnd = (request == null || request.atPeriodEnd() == null) ? true : request.atPeriodEnd();
        String subId = sub.getProviderSubscriptionId();
        try {
            SubscriptionResult result = billingProvider.cancelSubscription(new CancelSubscriptionRequest(
                    organizationId,
                    subId != null ? subId : "sub_" + organizationId,
                    atPeriodEnd));

            // CRITICAL: Webhook remains authoritative.
            // Do NOT directly mutate sub.setStatus("CANCELED") or sub.setCancelAtPeriodEnd(atPeriodEnd).
            // Verified webhook from provider will perform authoritative local state transition.

            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("atPeriodEnd", atPeriodEnd);
            meta.put("providerSubscriptionId", subId != null ? subId : "");
            meta.put("provider", billingProvider.getName());
            recordAuditLog(organizationId, actorUserId, "CANCELLATION_REQUESTED", sub.getPlan(),
                    atPeriodEnd ? sub.getPlan() : "FREE",
                    atPeriodEnd ? "Scheduled cancellation at period end requested" : "Immediate cancellation requested",
                    meta);

            return new CancelSubscriptionResponse(
                    result.status(),
                    atPeriodEnd,
                    atPeriodEnd ? "Subscription will cancel at current period end." : "Subscription cancelled immediately.");
        } catch (ApiException e) {
            throw e;
        } catch (BillingValidationException bve) {
            log.warn("Validation error in cancel for org={}: {}", organizationId, bve.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, bve.getMessage());
        } catch (Exception e) {
            log.error("Failed to cancel subscription for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to cancel subscription: " + e.getMessage());
        }
    }

    public CancelSubscriptionResponse cancelSubscription(UUID organizationId, CancelSubscriptionRequestDto request) {
        return cancelSubscription(organizationId, null, request);
    }

    /**
     * 6.6 Resume subscription: resumes a subscription scheduled for period-end cancellation.
     */
    @Transactional
    public ResumeSubscriptionResponse resumeSubscription(UUID organizationId, UUID actorUserId) {
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        Subscription sub = getOrCreateSubscription(organizationId);
        if (!sub.isCancelAtPeriodEnd() && !"PAST_DUE".equalsIgnoreCase(sub.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Subscription is not scheduled for cancellation.");
        }
        String subId = sub.getProviderSubscriptionId();
        try {
            SubscriptionResult result = billingProvider.resumeSubscription(new ResumeSubscriptionRequest(
                    organizationId,
                    subId != null ? subId : "sub_" + organizationId));

            // CRITICAL: Webhook remains authoritative.
            // Do NOT mutate sub.setStatus("ACTIVE") or sub.setCancelAtPeriodEnd(false).
            // Verified webhook from provider will perform authoritative local state transition.

            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("providerSubscriptionId", subId != null ? subId : "");
            meta.put("provider", billingProvider.getName());
            recordAuditLog(organizationId, actorUserId, "RESUME_REQUESTED", sub.getPlan(), sub.getPlan(),
                    "Subscription resume requested", meta);

            return new ResumeSubscriptionResponse(result.status(), false, "Subscription resumed successfully.");
        } catch (ApiException e) {
            throw e;
        } catch (BillingValidationException bve) {
            log.warn("Validation error in resume for org={}: {}", organizationId, bve.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, bve.getMessage());
        } catch (Exception e) {
            log.error("Failed to resume subscription for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to resume subscription: " + e.getMessage());
        }
    }

    public ResumeSubscriptionResponse resumeSubscription(UUID organizationId) {
        return resumeSubscription(organizationId, null);
    }

    /**
     * 6.14 Admin Override: strongly protected platform-admin endpoint to override subscription plan/status.
     * Narrowly scoped to plan/entitlement corrections, fully audited, evicts entitlement cache.
     */
    @Transactional
    public AdminBillingOverrideResponse adminOverrideSubscription(UUID actorUserId, AdminBillingOverrideRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Override request payload is required.");
        }
        if (request.organizationId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Target organization ID is required.");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A non-blank reason is mandatory for administrative overrides.");
        }
        if (request.plan() == null || request.plan().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Target plan is required.");
        }
        String targetPlan = request.plan().trim().toUpperCase(Locale.ROOT);
        if (PlanCatalog.get(targetPlan).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid target plan: " + targetPlan + ". Valid plans are: FREE, PRO, BUSINESS, PRIVATE_BETA.");
        }

        String targetStatus = request.status() != null && !request.status().isBlank()
                ? request.status().trim().toUpperCase(Locale.ROOT)
                : "ACTIVE";
        Set<String> validStatuses = Set.of("ACTIVE", "PAST_DUE", "CANCELED", "INCOMPLETE", "TRIALING");
        if (!validStatuses.contains(targetStatus)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid target status: " + targetStatus + ". Valid statuses: " + validStatuses);
        }

        UUID targetOrgId = request.organizationId();
        Subscription sub = getOrCreateSubscription(targetOrgId);
        String oldPlan = sub.getPlan();
        String oldStatus = sub.getStatus();

        // Mutate subscription authoritatively under platform admin prerogative
        sub.setPlan(targetPlan);
        sub.setStatus(targetStatus);
        if (request.cancelAtPeriodEnd() != null) {
            sub.setCancelAtPeriodEnd(request.cancelAtPeriodEnd());
        }
        subscriptionRepository.save(sub);

        // Evict cached plan context for the target organization
        if (entitlementService != null) {
            entitlementService.evictPlanCache(targetOrgId);
        }

        Instant now = Instant.now();

        // Immutable audit log
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("targetOrganizationId", targetOrgId.toString());
        metadata.put("actorUserId", actorUserId != null ? actorUserId.toString() : "system");
        metadata.put("previousPlan", oldPlan);
        metadata.put("newPlan", targetPlan);
        metadata.put("previousStatus", oldStatus);
        metadata.put("newStatus", targetStatus);
        metadata.put("reason", request.reason().trim());
        metadata.put("timestamp", now.toString());

        recordAuditLog(targetOrgId, actorUserId, "ADMIN_OVERRIDE", oldPlan, targetPlan, request.reason().trim(), metadata);

        log.info("Admin override applied: actor={} targetOrg={} plan: {} -> {}, status: {} -> {}, reason={}",
                actorUserId, targetOrgId, oldPlan, targetPlan, oldStatus, targetStatus, request.reason());

        return new AdminBillingOverrideResponse(
                targetOrgId,
                oldPlan,
                targetPlan,
                oldStatus,
                targetStatus,
                sub.isCancelAtPeriodEnd(),
                request.reason().trim(),
                actorUserId,
                now
        );
    }

    @Transactional(readOnly = true)
    public List<BillingAuditLog> getAuditLogs(UUID organizationId) {
        if (auditLogRepository == null) {
            return Collections.emptyList();
        }
        return auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    public Set<String> getSupportedCurrencies() {
        if (billingProvider != null && "razorpay".equalsIgnoreCase(billingProvider.getName())) {
            return RazorpayProperties.SUPPORTED_CURRENCIES;
        }
        return StripeProperties.SUPPORTED_CURRENCIES;
    }

    private String resolveAndValidateCurrency(String requestedCurrency, Subscription sub) {
        Set<String> supported = getSupportedCurrencies();
        if (requestedCurrency != null && !requestedCurrency.isBlank()) {
            String norm = requestedCurrency.trim().toUpperCase(Locale.ROOT);
            if (!supported.contains(norm)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Unsupported currency: " + requestedCurrency + ". Supported currencies: " + supported);
            }
            return norm;
        }

        // When currency is omitted by caller:
        // Use subscription's existing currency if supported, otherwise fallback to USD (or provider default)
        if (sub.getCurrency() != null && supported.contains(sub.getCurrency().toUpperCase(Locale.ROOT))) {
            return sub.getCurrency().toUpperCase(Locale.ROOT);
        }
        if (supported.contains("USD")) {
            return "USD";
        }
        return supported.iterator().next();
    }

    private String resolveAndValidateInterval(String requestedInterval, Subscription sub) {
        if (requestedInterval != null && !requestedInterval.isBlank()) {
            String norm = requestedInterval.trim().toLowerCase(Locale.ROOT);
            if (!"monthly".equals(norm) && !"yearly".equals(norm)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Invalid billing interval: " + requestedInterval + ". Supported intervals are 'monthly' and 'yearly'.");
            }
            return norm;
        }

        if (sub.getBillingInterval() != null) {
            String norm = sub.getBillingInterval().trim().toLowerCase(Locale.ROOT);
            if ("monthly".equals(norm) || "yearly".equals(norm)) {
                return norm;
            }
        }
        return "monthly";
    }

    private void validateConfiguredPriceId(String plan, String currency, String interval) {
        if (stripeProperties != null && "stripe".equalsIgnoreCase(billingProvider.getName())) {
            if (stripeProperties.getPriceId(plan, currency, interval).isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        String.format("No configured price found for plan=%s, currency=%s, interval=%s", plan, currency, interval));
            }
        }
        if (razorpayProperties != null && "razorpay".equalsIgnoreCase(billingProvider.getName())) {
            if (razorpayProperties.getPriceId(plan, currency, interval).isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        String.format("No configured price found for plan=%s, currency=%s, interval=%s", plan, currency, interval));
            }
        }
    }

    private void recordAuditLog(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String fromPlan,
            String toPlan,
            String reason,
            ObjectNode metadata) {
        if (auditLogRepository == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Billing audit logging repository is not available");
        }
        try {
            BillingAuditLog auditLog = BillingAuditLog.create(
                    organizationId,
                    actorUserId,
                    action,
                    fromPlan,
                    toPlan,
                    reason,
                    metadata);
            auditLogRepository.save(auditLog);
        } catch (ApiException e) {
            throw e;
        } catch (Exception ex) {
            log.error("Failed to persist billing audit log for org={} action={}: {}", organizationId, action, ex.getMessage(), ex);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to persist billing audit log: " + ex.getMessage());
        }
    }

    /**
     * 6.7 Invoices: retrieves customer invoices from provider.
     */
    @Transactional(readOnly = true)
    public List<InvoiceResponse> listInvoices(UUID organizationId) {
        Subscription sub = getOrCreateSubscription(organizationId);
        String customerId = sub.getProviderCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<InvoiceResult> providerInvoices = billingProvider.listInvoices(customerId, 20);
            if (providerInvoices == null) {
                return Collections.emptyList();
            }
            return providerInvoices.stream()
                    .map(inv -> new InvoiceResponse(
                            inv.invoiceId(),
                            inv.paidAt(),
                            inv.amountPaid(),
                            inv.currency(),
                            inv.status(),
                            inv.hostedInvoiceUrl(),
                            inv.pdfUrl()))
                    .toList();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list invoices for org={}", organizationId, e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to list invoices: " + e.getMessage());
        }
    }

    /**
     * 6.8 Founding offer: delegates to SubscriptionLifecycleService.applyFoundingOffer.
     */
    @Transactional
    public FoundingOfferResponse applyFoundingOffer(UUID organizationId) {
        if (subscriptionLifecycleService == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Subscription lifecycle service not available.");
        }
        BillingDiscount discount = subscriptionLifecycleService.applyFoundingOffer(organizationId);
        boolean active = Instant.now().isBefore(discount.getEndsAt());
        return new FoundingOfferResponse(
                discount.getCode(),
                discount.getDiscountPct(),
                discount.getStartsAt(),
                discount.getEndsAt(),
                active,
                "Founding member offer successfully applied (30% off for 12 months).");
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

        if (now.isAfter(end)) {
            BillingPeriod current = BillingPeriod.currentMonth();
            sub.setCurrentPeriodStart(current.start());
            sub.setCurrentPeriodEnd(current.end());
            subscriptionRepository.save(sub);
            if (entitlementService != null) {
                entitlementService.evictPlanCache(sub.getWorkspaceId());
            }
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
