package com.flowops.billing.dto;

/**
 * Overview of workspace billing status, localized currency, and live usage counters.
 */
public record BillingOverviewResponse(
        String plan,
        String status,
        String billingCountry,
        String billingCurrency,
        String currencySymbol,
        String paymentMethod,
        boolean isPrivateBeta,
        boolean isBillingEnabled,
        String betaNotice,
        WorkspaceUsageResponse usage) {
}
