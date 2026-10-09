package com.flowops.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.model.PlanDefinition;
import com.flowops.billing.provider.BillingProvider;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.BillingAuditLog;
import com.flowops.domain.BillingDiscount;
import com.flowops.domain.Subscription;
import com.flowops.domain.Workflow;
import com.flowops.repository.BillingAuditLogRepository;
import com.flowops.repository.BillingDiscountRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative subscription lifecycle orchestration service:
 * - Beta expiry and automated downgrade to FREE
 * - Deterministic workflow limit enforcement (preserving oldest active workflows)
 * - Execution history retention locking
 * - Audited beta expiry warnings (Day 30, 40, 44)
 * - Payment failure 7-day dunning grace period enforcement
 * - Single-application 30% founding member offer for 12 months
 */
@Service
public class SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);

    public static final String FOUNDING_OFFER_CODE = "FOUNDING_30";
    public static final int FOUNDING_OFFER_DISCOUNT_PCT = 30;
    public static final int FOUNDING_OFFER_DURATION_DAYS = 365;
    public static final int DUNNING_GRACE_PERIOD_DAYS = 7;

    private final SubscriptionRepository subscriptionRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final BillingDiscountRepository discountRepository;
    private final BillingAuditLogRepository auditLogRepository;
    private final BillingNoticeService billingNoticeService;
    private final EntitlementService entitlementService;
    private final BillingProvider billingProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public SubscriptionLifecycleService(
            SubscriptionRepository subscriptionRepository,
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            BillingDiscountRepository discountRepository,
            BillingAuditLogRepository auditLogRepository,
            BillingNoticeService billingNoticeService,
            @Autowired(required = false) EntitlementService entitlementService,
            BillingProvider billingProvider,
            ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.workflowRepository = workflowRepository;
        this.executionRepository = executionRepository;
        this.discountRepository = discountRepository;
        this.auditLogRepository = auditLogRepository;
        this.billingNoticeService = billingNoticeService;
        this.entitlementService = entitlementService;
        this.billingProvider = billingProvider;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // 1. Beta Expiry & Downgrade
    // =========================================================================

    /**
     * Finds subscriptions where private beta has expired (betaEndsAt <= now).
     * Downgrades them to FREE, enforces workflow limits (oldest 3 active),
     * locks history older than 3 days, records audit log, and evicts cache.
     * Operation is completely idempotent.
     */
    @Transactional
    public int expireBetaSubscriptions() {
        Instant now = Instant.now();
        List<Subscription> expired = subscriptionRepository.findExpiredBetaSubscriptions(now);
        int processedCount = 0;

        for (Subscription sub : expired) {
            UUID orgId = sub.getWorkspaceId();

            // Atomically claim and downgrade this subscription to eliminate concurrent race
            int updated = subscriptionRepository.downgradeExpiredBeta(sub.getId(), now);
            if (updated == 0) {
                continue;
            }

            log.info("Processing beta expiry downgrade for org={}", orgId);
            String oldPlan = sub.getPlan();

            // Audit trail
            recordAuditLog(
                    orgId,
                    null,
                    "BETA_EXPIRED_DOWNGRADE",
                    oldPlan,
                    "FREE",
                    "Private beta period ended",
                    null);

            // Evict entitlement cache
            if (entitlementService != null) {
                entitlementService.evictPlanCache(orgId);
            }

            // Enforce FREE workflow limits (3 workflows, oldest kept)
            int freeWorkflowLimit = PlanCatalog.get("FREE")
                    .map(p -> p.limits().activeWorkflows())
                    .orElse(3);
            enforceWorkflowLimits(orgId, freeWorkflowLimit);

            // Lock execution history older than FREE retention (3 days)
            int retentionDays = PlanCatalog.get("FREE")
                    .map(p -> p.limits().historyRetentionDays())
                    .orElse(3);
            lockExecutionHistory(orgId, retentionDays);

            processedCount++;
        }

        if (processedCount > 0) {
            log.info("Completed beta expiry downgrade for {} organizations", processedCount);
        }
        return processedCount;
    }

    // =========================================================================
    // 2. Workflow Limit Enforcement
    // =========================================================================

    /**
     * Enforces active workflow limits on plan downgrade or limit change.
     * Guarantees:
     * - Oldest-created workflows (createdAt ASC, id ASC) remain active (PUBLISHED).
     * - Excess workflows are deactivated to DRAFT.
     * - Never deletes workflows or version history.
     * - Operation is completely idempotent.
     */
    @Transactional
    public int enforceWorkflowLimits(UUID organizationId, int allowedLimit) {
        List<Workflow> published = workflowRepository.findPublishedByOrganizationIdOrderByCreatedAtAscIdAsc(organizationId);
        int totalPublished = published.size();

        if (totalPublished <= allowedLimit) {
            log.debug("Org {} active workflows ({}) within limit ({})", organizationId, totalPublished, allowedLimit);
            return 0;
        }

        int deactivatedCount = 0;
        for (int i = allowedLimit; i < totalPublished; i++) {
            Workflow excess = published.get(i);
            log.info("Deactivating excess workflow id={} name='{}' for org={} (limit={})",
                    excess.getId(), excess.getName(), organizationId, allowedLimit);
            excess.deactivate();
            workflowRepository.save(excess);
            deactivatedCount++;
        }

        log.info("Enforced workflow limit for org={}: kept {} active, deactivated {}",
                organizationId, allowedLimit, deactivatedCount);
        return deactivatedCount;
    }

    // =========================================================================
    // 3. Execution History Locking
    // =========================================================================

    /**
     * Locks execution records older than the plan's retention days.
     * Locked executions cannot be retried or canceled by users.
     * Data is never deleted. Idempotent.
     */
    @Transactional
    public int lockExecutionHistory(UUID organizationId, int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int locked = executionRepository.lockExecutionsOlderThan(organizationId, cutoff, Instant.now());
        if (locked > 0) {
            log.info("Locked {} historical executions older than {} days for org={}", locked, retentionDays, organizationId);
        }
        return locked;
    }

    /**
     * Global sweep to lock expired execution history for all organizations
     * based on their respective plan retention days.
     */
    @Transactional
    public int lockExpiredExecutionHistory() {
        Instant now = Instant.now();
        List<Subscription> subs = subscriptionRepository.findAll();
        int totalLocked = 0;

        for (Subscription sub : subs) {
            int retentionDays = PlanCatalog.get(sub.getPlan())
                    .map(PlanDefinition::limits)
                    .map(com.flowops.billing.model.PlanLimits::historyRetentionDays)
                    .orElse(3);
            Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
            totalLocked += executionRepository.lockExecutionsOlderThan(sub.getWorkspaceId(), cutoff, now);
        }

        return totalLocked;
    }

    // =========================================================================
    // 4. Beta Expiry Warnings
    // =========================================================================

    /**
     * Checks all active private beta subscriptions and dispatches warnings for
     * Day 30 (15 days left), Day 40 (5 days left), and Day 44 (1 day left).
     * Deduplication is persisted in the database via billing_notices unique constraint.
     */
    @Transactional
    public int sendBetaExpiryWarnings() {
        List<Subscription> betaSubs = subscriptionRepository.findActiveBetaSubscriptions();
        Instant now = Instant.now();
        int noticesDispatched = 0;

        for (Subscription sub : betaSubs) {
            Instant start = sub.getBetaStartedAt();
            Instant end = sub.getBetaEndsAt();
            if (start == null || end == null || now.isAfter(end)) {
                continue;
            }

            long daysElapsed = ChronoUnit.DAYS.between(start, now);
            long daysRemaining = Math.max(0, ChronoUnit.DAYS.between(now, end));

            // Day 44 warning (1 day left)
            if (daysElapsed >= 44 || daysRemaining <= 1) {
                if (billingNoticeService.recordAndPublishBetaExpiryNotice(
                        sub.getWorkspaceId(), start, 44, 1)) {
                    noticesDispatched++;
                }
            }
            // Day 40 warning (5 days left)
            else if (daysElapsed >= 40 || daysRemaining <= 5) {
                if (billingNoticeService.recordAndPublishBetaExpiryNotice(
                        sub.getWorkspaceId(), start, 40, 5)) {
                    noticesDispatched++;
                }
            }
            // Day 30 warning (15 days left)
            else if (daysElapsed >= 30 || daysRemaining <= 15) {
                if (billingNoticeService.recordAndPublishBetaExpiryNotice(
                        sub.getWorkspaceId(), start, 30, 15)) {
                    noticesDispatched++;
                }
            }
        }

        return noticesDispatched;
    }

    // =========================================================================
    // 5. Payment Failure / Dunning Grace Period
    // =========================================================================

    /**
     * Checks all PAST_DUE subscriptions.
     * Allows a 7-day grace period from pastDueSince during which the organization
     * remains usable.
     * After 7 days, automatically downgrades to FREE and enforces workflow limits.
     * Idempotent.
     */
    @Transactional
    public int processDunningAndGracePeriods() {
        List<Subscription> pastDueSubs = subscriptionRepository.findPastDueSubscriptions();
        Instant now = Instant.now();
        int downgradedCount = 0;

        for (Subscription sub : pastDueSubs) {
            UUID orgId = sub.getWorkspaceId();
            Instant pastDueSince = sub.getPastDueSince();

            if (pastDueSince == null) {
                sub.setPastDueSince(now);
                subscriptionRepository.save(sub);
                pastDueSince = now;
            }

            long daysInGrace = Duration.between(pastDueSince, now).toDays();

            if (daysInGrace < DUNNING_GRACE_PERIOD_DAYS) {
                log.info("Org {} is in dunning grace period (day {}/{})",
                        orgId, daysInGrace, DUNNING_GRACE_PERIOD_DAYS);
                continue;
            }

            // Grace period expired: downgrade to FREE
            int updated = subscriptionRepository.downgradePastDue(sub.getId(), now);
            if (updated == 0) {
                continue;
            }

            log.warn("Org {} dunning grace period ({} days) expired. Downgrading to FREE.",
                    orgId, DUNNING_GRACE_PERIOD_DAYS);

            String oldPlan = sub.getPlan();
            recordAuditLog(
                    orgId,
                    null,
                    "DUNNING_GRACE_EXPIRED_DOWNGRADE",
                    oldPlan,
                    "FREE",
                    "Payment failed and 7-day grace period expired",
                    null);

            if (entitlementService != null) {
                entitlementService.evictPlanCache(orgId);
            }

            int freeWorkflowLimit = PlanCatalog.get("FREE")
                    .map(p -> p.limits().activeWorkflows())
                    .orElse(3);
            enforceWorkflowLimits(orgId, freeWorkflowLimit);

            int retentionDays = PlanCatalog.get("FREE")
                    .map(p -> p.limits().historyRetentionDays())
                    .orElse(3);
            lockExecutionHistory(orgId, retentionDays);

            downgradedCount++;
        }

        return downgradedCount;
    }

    // =========================================================================
    // 6. Founding Member Offer
    // =========================================================================

    /**
     * Applies the 30% founding member discount for 12 months.
     * Validates eligibility window, prevents duplicate applications,
     * persists discount record in database, applies through provider,
     * and records audit log.
     */
    @Transactional
    public BillingDiscount applyFoundingOffer(UUID organizationId) {
        Subscription sub = subscriptionRepository.findByWorkspaceId(organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND, "Organization subscription not found."));

        Instant now = Instant.now();
        if (sub.getFoundingOfferEligibleUntil() == null || now.isAfter(sub.getFoundingOfferEligibleUntil())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Organization is not eligible for the founding member offer.");
        }

        if (discountRepository.existsByOrganizationIdAndCode(organizationId, FOUNDING_OFFER_CODE)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Founding offer has already been applied for this organization.");
        }

        Instant startsAt = now;
        Instant endsAt = now.plus(FOUNDING_OFFER_DURATION_DAYS, ChronoUnit.DAYS);
        int durationMonths = 12;

        // 1. Invoke billing provider discount operation
        com.flowops.billing.provider.model.DiscountResult providerResult = billingProvider.applyDiscount(
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        organizationId,
                        sub.getProviderCustomerId(),
                        sub.getProviderSubscriptionId(),
                        FOUNDING_OFFER_CODE,
                        FOUNDING_OFFER_DISCOUNT_PCT,
                        durationMonths));

        String providerCouponId = providerResult != null && providerResult.discountId() != null
                ? providerResult.discountId()
                : FOUNDING_OFFER_CODE;

        // 2. Persist local discount record only after provider success
        BillingDiscount discount = BillingDiscount.create(
                organizationId,
                FOUNDING_OFFER_CODE,
                FOUNDING_OFFER_DISCOUNT_PCT,
                startsAt,
                endsAt,
                providerCouponId);

        discount = discountRepository.save(discount);

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("discountPct", FOUNDING_OFFER_DISCOUNT_PCT);
        metadata.put("durationDays", FOUNDING_OFFER_DURATION_DAYS);
        metadata.put("durationMonths", durationMonths);
        metadata.put("providerDiscountId", providerCouponId);
        metadata.put("startsAt", startsAt.toString());
        metadata.put("endsAt", endsAt.toString());

        recordAuditLog(
                organizationId,
                null,
                "FOUNDING_OFFER_APPLIED",
                sub.getPlan(),
                sub.getPlan(),
                "Applied 30% founding discount valid for 12 months",
                metadata);

        log.info("Applied founding member offer (30% off for 12 months) via provider={} for org={}",
                billingProvider.getName(), organizationId);
        return discount;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void recordAuditLog(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String fromPlan,
            String toPlan,
            String reason,
            ObjectNode metadata) {
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
        } catch (Exception ex) {
            log.warn("Failed to record billing audit log for org={} action={}: {}", organizationId, action, ex.getMessage());
        }
    }
}
