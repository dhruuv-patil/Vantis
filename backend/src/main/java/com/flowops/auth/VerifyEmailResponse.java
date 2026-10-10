package com.flowops.auth;

/**
 * {@code POST /api/auth/verify-email} response body.
 */
public record VerifyEmailResponse(
        String message,
        String email) {
}
