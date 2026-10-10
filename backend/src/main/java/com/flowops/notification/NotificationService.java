package com.flowops.notification;

import com.flowops.api.NotificationEnvelopes;
import com.flowops.api.NotificationResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Notification;
import com.flowops.domain.NotificationLevel;
import com.flowops.repository.NotificationRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's in-app notification inbox.
 *
 * <p>Every read and write is scoped to <em>both</em> the caller's user id and their
 * current organization, taken from the principal: one member can never see or mutate
 * another's inbox, and switching orgs switches inboxes. A notification belonging to
 * someone else is indistinguishable from one that does not exist — both yield
 * {@link ErrorCode#NOTIFICATION_NOT_FOUND} (contract §2, anti-enumeration).
 *
 * <p>Notifications are only ever created by the server from real state (see
 * {@link #publish}, called by the execution engine when a run fails or parks on an
 * approval). There is no client-facing create endpoint, so nothing here can be forged.
 */
@Service
public class NotificationService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public NotificationEnvelopes.Notifications list(
            FlowOpsPrincipal principal, boolean unreadOnly, Integer limit) {
        List<NotificationResponse> rows = notifications
                .search(
                        principal.userId(),
                        principal.organizationId(),
                        unreadOnly,
                        PageRequest.of(0, clampLimit(limit)))
                .stream()
                .map(NotificationResponse::of)
                .toList();
        int unread = notifications.countByUserIdAndOrganizationIdAndReadAtIsNull(
                principal.userId(), principal.organizationId());
        return new NotificationEnvelopes.Notifications(rows, unread);
    }

    /** Marks one notification read. Idempotent; returns the refreshed inbox. */
    @Transactional
    public NotificationEnvelopes.Notifications markRead(FlowOpsPrincipal principal, UUID id) {
        Notification notification = notifications
                .findByIdAndUserIdAndOrganizationId(
                        id, principal.userId(), principal.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead();
        return list(principal, false, null);
    }

    /** Marks the caller's whole inbox read in one statement. Returns the refreshed inbox. */
    @Transactional
    public NotificationEnvelopes.Notifications markAllRead(FlowOpsPrincipal principal) {
        notifications.markAllRead(
                principal.userId(),
                principal.organizationId(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return list(principal, false, null);
    }

    /**
     * Server-side publish. Addresses the run's creator when it has one; a webhook-started
     * run has {@code created_by = null}, so it goes to the org's owners and admins instead
     * — the people who can act on it. Never called from a client-facing endpoint.
     * Uses REQUIRES_NEW so published notifications commit independently and survive
     * outer transaction rollbacks (e.g. when PLAN_LIMIT_EXCEEDED rolls back execution creation).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void publish(
            UUID organizationId,
            UUID userId,
            NotificationLevel level,
            String title,
            String body,
            String link) {
        List<UUID> addressees = userId != null
                ? List.of(userId)
                : notifications.findAdminUserIds(organizationId);
        String safeTitle = com.flowops.reliability.SlackAlertService.redactSensitiveData(title);
        String safeBody = com.flowops.reliability.SlackAlertService.redactSensitiveData(body);
        for (UUID addressee : addressees) {
            notifications.save(
                    Notification.create(organizationId, addressee, level, safeTitle, safeBody, link));
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
