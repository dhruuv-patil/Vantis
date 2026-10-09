package com.flowops.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.BillingPeriod;
import com.flowops.billing.model.EnforcementMode;
import com.flowops.billing.model.ExecutionReservationTicket;
import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.model.PlanDefinition;
import com.flowops.billing.model.PlanLimits;
import com.flowops.billing.model.ReservationResult;
import com.flowops.common.error.ApiException;
import com.flowops.domain.Invitation;
import com.flowops.domain.Subscription;
import com.flowops.repository.InvitationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Authoritative Entitlement Service for plan quotas and usage enforcement.
 * Enforces:
 * - Workflow activations (new activations only, grandfathering over-limit existing)
 * - Team member additions / invitations
 * - Execution reservations (with ticket-based refund-on-failure)
 * - AI generation quota
 * - AI anomaly investigation quota (monthly vs lifetime trial counter)
 *
 * Invariants:
 * - Fail-open: unexpected errors, missing subscription, or unknown plans log and allow the action.
 * - PLAN_LIMIT_EXCEEDED carries machine-readable details (limitKey, current, max, planId, suggestedPlan).
 * - Exactly-once notices deduplicated via unique DB table billing_notices after commit.
 * - In LOG mode: counts usage past the cap, never blocks, log lines only for notices.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final UsageMeterService usageMeterService;
    private final BillingPeriodService billingPeriodService;
    private final WorkflowRepository workflowRepository;
    private final InvitationRepository invitationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final BillingNoticeService billingNoticeService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${flowops.entitlements.enforcement:${ENTITLEMENTS_ENFORCEMENT:log}}")
    private String enforcementModeConfig = "log";

    @Autowired
    public EntitlementService(
            SubscriptionRepository subscriptionRepository,
            UsageMeterService usageMeterService,
            BillingPeriodService billingPeriodService,
            WorkflowRepository workflowRepository,
            InvitationRepository invitationRepository,
            OrganizationMemberRepository memberRepository,
            @Autowired(required = false) BillingNoticeService billingNoticeService,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.subscriptionRepository = subscriptionRepository;
        this.usageMeterService = usageMeterService;
        this.billingPeriodService = billingPeriodService;
        this.workflowRepository = workflowRepository;
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.billingNoticeService = billingNoticeService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    // Package-private constructor for deterministic testing without mutable state
    public EntitlementService(
            SubscriptionRepository subscriptionRepository,
            UsageMeterService usageMeterService,
            BillingPeriodService billingPeriodService,
            WorkflowRepository workflowRepository,
            InvitationRepository invitationRepository,
            OrganizationMemberRepository memberRepository,
            BillingNoticeService billingNoticeService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            String enforcementModeConfig) {
        this.subscriptionRepository = subscriptionRepository;
        this.usageMeterService = usageMeterService;
        this.billingPeriodService = billingPeriodService;
        this.workflowRepository = workflowRepository;
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.billingNoticeService = billingNoticeService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enforcementModeConfig = enforcementModeConfig;
    }

    public EnforcementMode getEnforcementMode() {
        return EnforcementMode.fromString(enforcementModeConfig);
    }

    public record CachedPlanContext(
            String planId,
            boolean isBeta,
            boolean betaExpired,
            int workflowLimit,
            int executionLimit,
            int aiGenerationLimit,
            int aiInvestigationMonthlyLimit,
            int aiInvestigationTrialLimit,
            int seatLimit,
            int workspaceLimit,
            int historyRetentionDays,
            int workflowVersionsKept,
            Set<String> allowedChannels,
            String maxAnomalyMode,
            Instant periodStart,
            Instant periodEnd) {
    }

    /**
     * Resolves plan context and limits with Redis caching (TTL = min(60s, periodEnd - now))
     * and authoritative fail-open behavior.
     */
    public Optional<CachedPlanContext> resolvePlanContext(UUID organizationId) {
        String cacheKey = "entitlements:plan:" + organizationId;
        if (redisTemplate != null) {
            try {
                String cachedJson = redisTemplate.opsForValue().get(cacheKey);
                if (cachedJson != null) {
                    return Optional.of(objectMapper.readValue(cachedJson, CachedPlanContext.class));
                }
            } catch (Exception e) {
                log.warn("Redis read failed for entitlements plan cache org={} (failing open to DB): {}",
                        organizationId, e.getMessage());
                countFailOpen("redis_error");
            }
        }

        try {
            Optional<Subscription> subOpt = subscriptionRepository.findByWorkspaceId(organizationId);
            if (subOpt.isEmpty()) {
                log.error("Missing subscription row for organizationId={}. Entitlements failing open.", organizationId);
                countFailOpen("missing_subscription");
                return Optional.empty();
            }

            Subscription sub = subOpt.get();
            String planId = sub.getPlan();
            Optional<PlanDefinition> planDefOpt = PlanCatalog.get(planId);
            if (planDefOpt.isEmpty()) {
                log.error("Unknown planId='{}' for organizationId={}. Entitlements failing open.", planId, organizationId);
                countFailOpen("unknown_plan");
                return Optional.empty();
            }

            PlanDefinition planDef = planDefOpt.get();
            BillingPeriod period = billingPeriodService.resolvePeriod(sub);
            boolean isBeta = "PRIVATE_BETA".equalsIgnoreCase(planDef.id());
            boolean betaExpired = false;
            PlanLimits effectiveLimits = planDef.limits();

            if (isBeta && sub.getBetaEndsAt() != null && Instant.now().isAfter(sub.getBetaEndsAt())) {
                // Beta expired: treat as FREE limits for new activations/runs, never delete data
                betaExpired = true;
                PlanDefinition freePlan = PlanCatalog.get("FREE").orElseThrow();
                effectiveLimits = freePlan.limits();
            }

            CachedPlanContext ctx = new CachedPlanContext(
                    planDef.id(),
                    isBeta,
                    betaExpired,
                    effectiveLimits.activeWorkflows(),
                    effectiveLimits.executionsPerMonth(),
                    effectiveLimits.aiGenerations(),
                    effectiveLimits.aiInvestigationsMonthly(),
                    effectiveLimits.aiInvestigationsLifetimeTrial(),
                    effectiveLimits.seats(),
                    effectiveLimits.workspaces(),
                    effectiveLimits.historyRetentionDays(),
                    effectiveLimits.workflowVersionsKept(),
                    effectiveLimits.allowedChannels(),
                    effectiveLimits.maxAnomalyMode(),
                    period.start(),
                    period.end());

            if (redisTemplate != null) {
                try {
                    String json = objectMapper.writeValueAsString(ctx);
                    Instant now = Instant.now();
                    Duration remaining = Duration.between(now, period.end());
                    Duration ttl = (remaining.isNegative() || remaining.isZero())
                            ? Duration.ofSeconds(1)
                            : (remaining.compareTo(Duration.ofSeconds(60)) < 0 ? remaining : Duration.ofSeconds(60));
                    redisTemplate.opsForValue().set(cacheKey, json, ttl);
                } catch (Exception e) {
                    log.warn("Redis write failed for entitlements plan cache: {}", e.getMessage());
                    countFailOpen("redis_error");
                }
            }

            return Optional.of(ctx);
        } catch (Exception ex) {
            log.error("DB error resolving plan context for organizationId={}. Entitlements failing open.",
                    organizationId, ex);
            countFailOpen("db_error");
            return Optional.empty();
        }
    }

    public void evictPlanCache(UUID organizationId) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete("entitlements:plan:" + organizationId);
            } catch (Exception e) {
                log.warn("Failed to evict entitlements plan cache for org={}: {}", organizationId, e.getMessage());
            }
        }
    }

    // =========================================================================
    // Workflow Activation Enforcement
    // =========================================================================

    public boolean canActivateWorkflow(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        long active = workflowRepository.countActiveByOrganizationId(organizationId);
        return active < ctxOpt.get().workflowLimit();
    }

    public void assertCanActivateWorkflow(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return;
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return; // Fail open
        }

        CachedPlanContext ctx = ctxOpt.get();
        long activeCount = workflowRepository.countActiveByOrganizationId(organizationId);

        if (activeCount >= ctx.workflowLimit()) {
            if (mode == EnforcementMode.LOG) {
                log.info("ENTITLEMENTS_LOG: Org {} would be blocked from activating workflow (active={}, limit={})",
                        organizationId, activeCount, ctx.workflowLimit());
                return;
            }

            throw ApiException.planLimitExceeded(
                    "activeWorkflows",
                    activeCount,
                    ctx.workflowLimit(),
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Active workflow limit reached for your plan ("
                            + activeCount + " / " + ctx.workflowLimit() + ").");
        }
    }

    // =========================================================================
    // Team Members / Invitations Enforcement
    // =========================================================================

    public boolean canAddMember(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        long currentMembers = memberRepository.countByOrganizationId(organizationId);
        long pendingInvites = invitationRepository.countByOrganizationIdAndStatusAndExpiresAtAfter(
                organizationId, Invitation.Status.PENDING, Instant.now());
        return (currentMembers + pendingInvites) < ctxOpt.get().seatLimit();
    }

    public void assertCanAddMember(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return;
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return; // Fail open
        }

        CachedPlanContext ctx = ctxOpt.get();
        long currentMembers = memberRepository.countByOrganizationId(organizationId);
        long pendingInvites = invitationRepository.countByOrganizationIdAndStatusAndExpiresAtAfter(
                organizationId, Invitation.Status.PENDING, Instant.now());
        long totalOccupied = currentMembers + pendingInvites;

        if (totalOccupied >= ctx.seatLimit()) {
            if (mode == EnforcementMode.LOG) {
                log.info("ENTITLEMENTS_LOG: Org {} would be blocked from adding member (members={}, pendingInvites={}, limit={})",
                        organizationId, currentMembers, pendingInvites, ctx.seatLimit());
                return;
            }

            throw ApiException.planLimitExceeded(
                    "seats",
                    totalOccupied,
                    ctx.seatLimit(),
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Team member limit reached for your plan ("
                            + totalOccupied + " / " + ctx.seatLimit() + ").");
        }
    }

    public void assertCanInviteMember(UUID organizationId) {
        assertCanAddMember(organizationId);
    }

    // =========================================================================
    // Execution Reservation & Refund
    // =========================================================================

    public boolean canRunExecution(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        CachedPlanContext ctx = ctxOpt.get();
        long current = usageMeterService.getCurrentUsage(organizationId, ctx.periodStart(), "executions");
        return current < ctx.executionLimit();
    }

    public ExecutionReservationTicket reserveExecution(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return ExecutionReservationTicket.notReserved();
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return ExecutionReservationTicket.notReserved(); // Fail open
        }

        CachedPlanContext ctx = ctxOpt.get();
        long limit = ctx.executionLimit();

        if (mode == EnforcementMode.LOG) {
            // In LOG mode: count usage past the cap (so the meter is accurate), never block
            ReservationResult result = usageMeterService.reserveExecution(
                    organizationId, ctx.periodStart(), ctx.periodEnd(), Long.MAX_VALUE);

            checkAndSendNotices(organizationId, ctx, result.currentUsage(), limit, false);

            if (result.currentUsage() > limit) {
                log.info("ENTITLEMENTS_LOG: Org {} is over execution limit (usage={}, limit={})",
                        organizationId, result.currentUsage(), limit);
            }

            return ExecutionReservationTicket.reserved(ctx.periodStart());
        }

        // ENFORCE mode
        ReservationResult result = usageMeterService.reserveExecution(
                organizationId, ctx.periodStart(), ctx.periodEnd(), limit);

        checkAndSendNotices(organizationId, ctx, result.currentUsage(), limit, true);

        if (!result.allowed()) {
            throw ApiException.planLimitExceeded(
                    "executionsPerMonth",
                    result.currentUsage(),
                    limit,
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Monthly execution limit reached for your plan ("
                            + result.currentUsage() + " / " + limit + ").");
        }

        return ExecutionReservationTicket.reserved(ctx.periodStart());
    }

    public void refundExecution(UUID organizationId, ExecutionReservationTicket ticket) {
        if (ticket != null && ticket.reserved() && ticket.periodStart() != null) {
            usageMeterService.refundExecution(organizationId, ticket.periodStart());
        }
    }

    // =========================================================================
    // AI Generation Reservation & Refund
    // =========================================================================

    public boolean canGenerateWorkflowWithAi(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        CachedPlanContext ctx = ctxOpt.get();
        long current = usageMeterService.getCurrentUsage(organizationId, ctx.periodStart(), "ai_generations");
        return current < ctx.aiGenerationLimit();
    }

    public ExecutionReservationTicket reserveAiGeneration(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return ExecutionReservationTicket.notReserved();
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return ExecutionReservationTicket.notReserved();
        }

        CachedPlanContext ctx = ctxOpt.get();
        long limit = ctx.aiGenerationLimit();

        if (mode == EnforcementMode.LOG) {
            ReservationResult result = usageMeterService.reserveAiGeneration(
                    organizationId, ctx.periodStart(), ctx.periodEnd(), Long.MAX_VALUE);
            if (result.currentUsage() > limit) {
                log.info("ENTITLEMENTS_LOG: Org {} is over AI generation limit (usage={}, limit={})",
                        organizationId, result.currentUsage(), limit);
            }
            return ExecutionReservationTicket.reserved(ctx.periodStart());
        }

        ReservationResult result = usageMeterService.reserveAiGeneration(
                organizationId, ctx.periodStart(), ctx.periodEnd(), limit);

        if (!result.allowed()) {
            throw ApiException.planLimitExceeded(
                    "aiGenerations",
                    result.currentUsage(),
                    limit,
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Monthly AI generation limit reached for your plan ("
                            + result.currentUsage() + " / " + limit + ").");
        }

        return ExecutionReservationTicket.reserved(ctx.periodStart());
    }

    public void refundAiGeneration(UUID organizationId, ExecutionReservationTicket ticket) {
        if (ticket != null && ticket.reserved() && ticket.periodStart() != null) {
            usageMeterService.refundAiGeneration(organizationId, ticket.periodStart());
        }
    }

    // =========================================================================
    // AI Investigation Reservation & Refund
    // =========================================================================

    public record InvestigationReservationTicket(
            boolean reserved,
            boolean isTrial,
            Instant periodStart) {

        public static InvestigationReservationTicket notReserved() {
            return new InvestigationReservationTicket(false, false, null);
        }
    }

    public boolean canRunAiInvestigation(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        CachedPlanContext ctx = ctxOpt.get();
        if (ctx.aiInvestigationMonthlyLimit() > 0) {
            long current = usageMeterService.getCurrentUsage(organizationId, ctx.periodStart(), "ai_investigations");
            return current < ctx.aiInvestigationMonthlyLimit();
        }
        long trialUsed = usageMeterService.getLifetimeTrialUsage(organizationId);
        return trialUsed < ctx.aiInvestigationTrialLimit();
    }

    public InvestigationReservationTicket reserveAiInvestigation(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return InvestigationReservationTicket.notReserved();
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return InvestigationReservationTicket.notReserved();
        }

        CachedPlanContext ctx = ctxOpt.get();
        int monthlyLimit = ctx.aiInvestigationMonthlyLimit();

        if (monthlyLimit > 0) {
            if (mode == EnforcementMode.LOG) {
                ReservationResult res = usageMeterService.reserveAiInvestigationMonthly(
                        organizationId, ctx.periodStart(), ctx.periodEnd(), Integer.MAX_VALUE);
                if (res.currentUsage() > monthlyLimit) {
                    log.info("ENTITLEMENTS_LOG: Org {} is over AI investigation monthly quota (usage={}, limit={})",
                            organizationId, res.currentUsage(), monthlyLimit);
                }
                return new InvestigationReservationTicket(true, false, ctx.periodStart());
            }

            ReservationResult res = usageMeterService.reserveAiInvestigationMonthly(
                    organizationId, ctx.periodStart(), ctx.periodEnd(), monthlyLimit);
            if (!res.allowed()) {
                throw ApiException.planLimitExceeded(
                        "aiInvestigations",
                        res.currentUsage(),
                        monthlyLimit,
                        ctx.planId(),
                        PlanCatalog.suggestedUpgrade(ctx.planId()),
                        "Monthly AI investigation quota reached ("
                                + res.currentUsage() + " / " + monthlyLimit + ").");
            }
            return new InvestigationReservationTicket(true, false, ctx.periodStart());
        }

        // Monthly quota == 0 (e.g. FREE plan). Use lifetime trial allowance
        int trialLimit = ctx.aiInvestigationTrialLimit();
        if (mode == EnforcementMode.LOG) {
            ReservationResult res = usageMeterService.reserveAiInvestigationTrial(organizationId, Integer.MAX_VALUE);
            if (res.currentUsage() > trialLimit) {
                log.info("ENTITLEMENTS_LOG: Org {} is over lifetime trial investigation (used={}, limit={})",
                        organizationId, res.currentUsage(), trialLimit);
            }
            return new InvestigationReservationTicket(true, true, null);
        }

        ReservationResult res = usageMeterService.reserveAiInvestigationTrial(organizationId, trialLimit);
        if (!res.allowed()) {
            throw ApiException.planLimitExceeded(
                    "aiInvestigationsTrial",
                    res.currentUsage(),
                    trialLimit,
                    ctx.planId(),
                    "PRO",
                    "Lifetime trial AI investigation quota reached ("
                            + res.currentUsage() + " / " + trialLimit + "). Upgrade to Pro for monthly investigations.");
        }
        return new InvestigationReservationTicket(true, true, null);
    }

    public void refundAiInvestigation(UUID organizationId, InvestigationReservationTicket ticket) {
        if (ticket == null || !ticket.reserved()) {
            return;
        }
        if (ticket.isTrial()) {
            usageMeterService.refundAiInvestigationTrial(organizationId);
        } else if (ticket.periodStart() != null) {
            usageMeterService.refundAiInvestigationMonthly(organizationId, ticket.periodStart());
        }
    }

    // =========================================================================
    // Workspaces, Channels, Retention & Anomaly Mode
    // =========================================================================

    public boolean canAddWorkspace(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        return 1 <= ctxOpt.get().workspaceLimit();
    }

    public void assertCanAddWorkspace(UUID organizationId) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return;
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return;
        }

        CachedPlanContext ctx = ctxOpt.get();
        if (1 >= ctx.workspaceLimit() && ctx.workspaceLimit() != Integer.MAX_VALUE) {
            if (mode == EnforcementMode.LOG) {
                log.info("ENTITLEMENTS_LOG: Org {} workspace limit reached (limit={})",
                        organizationId, ctx.workspaceLimit());
                return;
            }

            throw ApiException.planLimitExceeded(
                    "workspaces",
                    1,
                    ctx.workspaceLimit(),
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Workspace limit reached for your plan (1 / " + ctx.workspaceLimit() + ").");
        }
    }

    public boolean canUseChannel(UUID organizationId, String channelType) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        CachedPlanContext ctx = ctxOpt.get();
        if (channelType == null || channelType.isBlank()) {
            return true;
        }
        return ctx.allowedChannels().contains(channelType.trim().toUpperCase());
    }

    public void assertCanUseChannel(UUID organizationId, String channelType) {
        EnforcementMode mode = getEnforcementMode();
        if (mode == EnforcementMode.OFF) {
            return;
        }

        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return;
        }

        CachedPlanContext ctx = ctxOpt.get();
        if (channelType == null || channelType.isBlank()) {
            return;
        }
        String ch = channelType.trim().toUpperCase();

        if (!ctx.allowedChannels().contains(ch)) {
            if (mode == EnforcementMode.LOG) {
                log.info("ENTITLEMENTS_LOG: Org {} unsupported channel '{}' on plan {}",
                        organizationId, ch, ctx.planId());
                return;
            }

            throw ApiException.planLimitExceeded(
                    "channels",
                    0,
                    0,
                    ctx.planId(),
                    PlanCatalog.suggestedUpgrade(ctx.planId()),
                    "Notification channel '" + ch + "' is not supported on the " + ctx.planId() + " plan.");
        }
    }

    public Instant historyCutoff(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        int days = ctxOpt.map(CachedPlanContext::historyRetentionDays).orElse(PlanCatalog.FREE_HISTORY_RETENTION_DAYS);
        return Instant.now().minus(Duration.ofDays(days));
    }

    public boolean canUseAnomalyMode(UUID organizationId, String anomalyMode) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        if (ctxOpt.isEmpty()) {
            return true;
        }
        CachedPlanContext ctx = ctxOpt.get();
        if (anomalyMode == null || anomalyMode.isBlank()) {
            return true;
        }
        String requested = anomalyMode.trim().toLowerCase();
        String maxMode = ctx.maxAnomalyMode() != null ? ctx.maxAnomalyMode().trim().toLowerCase() : "basic";
        if ("tunable".equalsIgnoreCase(maxMode)) {
            return true;
        }
        if ("full".equalsIgnoreCase(maxMode)) {
            return !"tunable".equalsIgnoreCase(requested);
        }
        return "basic".equalsIgnoreCase(requested);
    }

    public int versionsToKeep(UUID organizationId) {
        Optional<CachedPlanContext> ctxOpt = resolvePlanContext(organizationId);
        return ctxOpt.map(CachedPlanContext::workflowVersionsKept).orElse(PlanCatalog.FREE_WORKFLOW_VERSIONS_KEPT);
    }

    // =========================================================================
    // Notifications & Deduplication
    // =========================================================================

    private void checkAndSendNotices(
            UUID organizationId,
            CachedPlanContext ctx,
            long currentUsage,
            long limit,
            boolean isEnforcing) {
        if (limit <= 0 || limit == Integer.MAX_VALUE) {
            return;
        }

        long threshold80 = (long) Math.ceil(limit * 0.8);
        long threshold100 = limit;

        if (currentUsage >= threshold100) {
            triggerNotice(organizationId, ctx, 100, currentUsage, limit, isEnforcing);
        } else if (currentUsage >= threshold80) {
            triggerNotice(organizationId, ctx, 80, currentUsage, limit, isEnforcing);
        }
    }

    private void triggerNotice(
            UUID organizationId,
            CachedPlanContext ctx,
            int pct,
            long currentUsage,
            long limit,
            boolean isEnforcing) {
        if (!isEnforcing) {
            // In LOG mode: log lines only; no in-app notices, no emails
            log.info("ENTITLEMENTS_LOG: Org {} reached {}% threshold (usage={}, limit={})",
                    organizationId, pct, currentUsage, limit);
            return;
        }

        if (billingNoticeService != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        billingNoticeService.recordAndPublishNotice(
                                organizationId, ctx.periodStart(), pct, "executions", currentUsage, limit, true);
                    }
                });
            } else {
                billingNoticeService.recordAndPublishNotice(
                        organizationId, ctx.periodStart(), pct, "executions", currentUsage, limit, true);
            }
        }
    }

    private void countFailOpen(String reason) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("entitlements.fail_open", "reason", reason).increment();
            } catch (Exception ignored) {
            }
        }
    }
}
