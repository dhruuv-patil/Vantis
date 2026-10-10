package com.flowops.billing.service;

import com.flowops.domain.BillingNotice;
import com.flowops.domain.NotificationLevel;
import com.flowops.notification.NotificationService;
import com.flowops.repository.BillingNoticeRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists billing notice deduplication row in the same transaction as the in-app notification.
 * Invoked after transaction commit to ensure no nested transactions or held connections during request processing.
 */
@Service
public class BillingNoticeService {

    private static final Logger log = LoggerFactory.getLogger(BillingNoticeService.class);

    private final BillingNoticeRepository noticeRepository;
    private final NotificationService notificationService;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final BillingNoticeMailer noticeMailer;

    public BillingNoticeService(
            BillingNoticeRepository noticeRepository,
            NotificationService notificationService,
            OrganizationMemberRepository memberRepository,
            UserRepository userRepository,
            BillingNoticeMailer noticeMailer) {
        this.noticeRepository = noticeRepository;
        this.notificationService = notificationService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.noticeMailer = noticeMailer;
    }

    /**
     * Inserts the billing notice dedupe row and in-app notification in an independent transaction.
     * If the notice has already been recorded for (org, period_start, threshold, kind), the unique
     * constraint prevents duplicate insertion and notification.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAndPublishNotice(
            UUID organizationId,
            Instant periodStart,
            int threshold,
            String kind,
            long currentUsage,
            long limit,
            boolean isEnforcing) {

        if (noticeRepository.findByOrganizationIdAndPeriodStartAndThresholdAndKind(organizationId, periodStart, threshold, kind).isPresent()) {
            return;
        }

        try {
            BillingNotice notice = BillingNotice.create(organizationId, periodStart, threshold, kind);
            noticeRepository.saveAndFlush(notice);
        } catch (DataIntegrityViolationException dive) {
            log.debug("Billing notice already recorded for org={} threshold={} kind={}", organizationId, threshold, kind);
            return;
        } catch (Exception ex) {
            log.warn("Failed to record billing notice row for org={}: {}", organizationId, ex.getMessage());
            return;
        }

        String title = threshold == 100
                ? "Monthly Execution Limit Reached"
                : "Monthly Execution Usage at " + threshold + "%";

        String consequence = isEnforcing
                ? "New executions are paused until the next cycle or upgrade."
                : "Please review your usage.";

        String body = threshold == 100
                ? "Your workspace has reached 100% of its monthly execution allowance ("
                + currentUsage + " / " + limit + "). " + consequence
                : "Your workspace has reached " + threshold + "% of its monthly execution allowance ("
                + currentUsage + " / " + limit + ").";

        NotificationLevel level = threshold == 100 ? NotificationLevel.ERROR : NotificationLevel.WARN;

        // In-app notification in same transaction as dedupe row
        try {
            notificationService.publish(
                    organizationId,
                    null,
                    level,
                    title,
                    body,
                    "/billing");
        } catch (Exception ex) {
            log.warn("Failed to publish in-app billing notification: {}", ex.getMessage());
        }

        // Async email to organization owner
        try {
            Optional<UUID> ownerUserIdOpt = memberRepository.findOwnerUserId(organizationId);
            if (ownerUserIdOpt.isPresent()) {
                userRepository.findById(ownerUserIdOpt.get()).ifPresent(ownerUser -> {
                    noticeMailer.sendBillingEmail(ownerUser.getEmail(), title, body);
                });
            }
        } catch (Exception ex) {
            log.warn("Failed to trigger billing email notice: {}", ex.getMessage());
        }
    }

    /**
     * Deduplicated notice for private beta expiry warnings (Day 30, Day 40, Day 44).
     * Enforced persistently by the unique constraint on (organization_id, period_start, threshold, kind).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordAndPublishBetaExpiryNotice(
            UUID organizationId,
            Instant betaStartedAt,
            int dayThreshold,
            int daysRemaining) {

        String kind = "BETA_EXPIRY_WARNING";
        Instant normalizedPeriodStart = betaStartedAt.truncatedTo(ChronoUnit.MILLIS);
        if (noticeRepository.findByOrganizationIdAndPeriodStartAndThresholdAndKind(organizationId, normalizedPeriodStart, dayThreshold, kind).isPresent()) {
            return false;
        }

        try {
            BillingNotice notice = BillingNotice.create(organizationId, normalizedPeriodStart, dayThreshold, kind);
            noticeRepository.saveAndFlush(notice);
        } catch (DataIntegrityViolationException dive) {
            log.debug("Beta expiry notice already recorded for org={} dayThreshold={}", organizationId, dayThreshold);
            return false;
        } catch (Exception ex) {
            log.warn("Failed to record beta notice row for org={}: {}", organizationId, ex.getMessage());
            return false;
        }

        String title = "Private Beta Ending in " + daysRemaining + (daysRemaining == 1 ? " Day" : " Days");
        String body = "Your private beta period ends in " + daysRemaining + (daysRemaining == 1 ? " day" : " days")
                + ". Upgrade to PRO to retain active workflows and higher limits.";
        NotificationLevel level = daysRemaining <= 1 ? NotificationLevel.ERROR : NotificationLevel.WARN;

        try {
            notificationService.publish(organizationId, null, level, title, body, "/billing");
        } catch (Exception ex) {
            log.warn("Failed to publish in-app beta notification: {}", ex.getMessage());
        }

        try {
            Optional<UUID> ownerUserIdOpt = memberRepository.findOwnerUserId(organizationId);
            if (ownerUserIdOpt.isPresent()) {
                userRepository.findById(ownerUserIdOpt.get()).ifPresent(ownerUser -> {
                    noticeMailer.sendBillingEmail(ownerUser.getEmail(), title, body);
                });
            }
        } catch (Exception ex) {
            log.warn("Failed to trigger beta email notice: {}", ex.getMessage());
        }

        return true;
    }
}
