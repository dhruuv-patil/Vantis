package com.flowops.billing.provider.model;

public record WebhookProcessResult(
        String eventId,
        String status,
        String message
) {
    public static WebhookProcessResult processed(String eventId, String subStatus) {
        return new WebhookProcessResult(eventId, "PROCESSED", "Event processed successfully. Subscription status: " + subStatus);
    }

    public static WebhookProcessResult ignoredDuplicate(String eventId) {
        return new WebhookProcessResult(eventId, "IGNORED_DUPLICATE", "Duplicate event ignored idempotently");
    }

    public static WebhookProcessResult ignoredOutOfOrder(String eventId) {
        return new WebhookProcessResult(eventId, "IGNORED_OUT_OF_ORDER", "Out-of-order event ignored to prevent state regression");
    }

    public static WebhookProcessResult failed(String eventId, String reason) {
        return new WebhookProcessResult(eventId, "FAILED", reason);
    }
}
