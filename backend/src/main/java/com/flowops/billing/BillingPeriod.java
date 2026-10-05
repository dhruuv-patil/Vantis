package com.flowops.billing;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Standard billing cycle window.
 * Default calendar month calculation in UTC for workspaces without customized period anchors.
 */
public record BillingPeriod(Instant start, Instant end) {

    public static BillingPeriod currentMonth() {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant start = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new BillingPeriod(start, end);
    }
}
