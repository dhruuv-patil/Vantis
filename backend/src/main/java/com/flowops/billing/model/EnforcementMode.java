package com.flowops.billing.model;

/**
 * Entitlements enforcement mode:
 * - OFF: skips metering and limit checks entirely
 * - LOG: counts usage, records "would block" without blocking
 * - ENFORCE: strict enforcement, blocks on limit exceeded with PLAN_LIMIT_EXCEEDED
 */
public enum EnforcementMode {
    OFF,
    LOG,
    ENFORCE;

    public static EnforcementMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOG;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOG;
        }
    }
}
