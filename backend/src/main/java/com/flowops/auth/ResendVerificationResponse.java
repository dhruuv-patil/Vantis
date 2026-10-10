package com.flowops.auth;

/**
 * {@code POST /api/auth/resend-verification} response body.
 */
public record ResendVerificationResponse(
        String message) {
}
