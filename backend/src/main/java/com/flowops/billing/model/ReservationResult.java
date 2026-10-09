package com.flowops.billing.model;

/**
 * Result of an atomic usage reservation.
 * @param allowed whether the action is permitted to proceed
 * @param reserved whether the usage counter was incremented
 * @param currentUsage current usage counter value after this operation
 * @param limit the configured limit
 */
public record ReservationResult(
        boolean allowed,
        boolean reserved,
        long currentUsage,
        long limit) {
}
