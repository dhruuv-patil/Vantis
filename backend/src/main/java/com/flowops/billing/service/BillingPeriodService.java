package com.flowops.billing.service;

import com.flowops.billing.BillingPeriod;
import com.flowops.domain.Subscription;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Single shared source of truth for the active usage billing period.
 * - FREE / PRIVATE_BETA: calendar month (or beta_ends_at boundary for beta)
 * - PAID: subscription current_period_start to current_period_end
 * If >7 days past current_period_end, logs a loud warning before falling back to calendar month.
 */
@Service
public class BillingPeriodService {

    private static final Logger log = LoggerFactory.getLogger(BillingPeriodService.class);
    private static final long STALE_PERIOD_DAYS_THRESHOLD = 7L;

    public BillingPeriod resolvePeriod(Subscription subscription) {
        if (subscription == null) {
            return BillingPeriod.currentMonth();
        }

        String plan = subscription.getPlan() != null ? subscription.getPlan().toUpperCase() : "FREE";

        // For FREE and PRIVATE_BETA, usage resets on a calendar month boundary.
        // For PRIVATE_BETA, the beta itself ends at beta_ends_at, but usage meter cycles are monthly.
        if ("FREE".equals(plan) || "PRIVATE_BETA".equals(plan)) {
            return BillingPeriod.currentMonth();
        }

        Instant start = subscription.getCurrentPeriodStart();
        Instant end = subscription.getCurrentPeriodEnd();
        Instant now = Instant.now();

        if (start == null || end == null) {
            log.warn("Subscription for org={} on plan={} has null period bounds; falling back to calendar month.",
                    subscription.getWorkspaceId(), plan);
            return BillingPeriod.currentMonth();
        }

        // If stale by more than 7 days, log loud warning and fall back to current calendar month
        if (now.isAfter(end)) {
            long daysPast = ChronoUnit.DAYS.between(end, now);
            if (daysPast > STALE_PERIOD_DAYS_THRESHOLD) {
                log.error("CRITICAL: Subscription for org={} is {} days past current_period_end ({}) without renewal! Falling back to calendar month.",
                        subscription.getWorkspaceId(), daysPast, end);
                return BillingPeriod.currentMonth();
            }
        }

        return new BillingPeriod(start, end);
    }
}
