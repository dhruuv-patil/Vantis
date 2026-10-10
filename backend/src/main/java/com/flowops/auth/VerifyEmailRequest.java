package com.flowops.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/verify-email} request body.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "Verification token is required.")
        @Size(min = 1, max = 255, message = "Token must be valid.")
        String token) {

    public VerifyEmailRequest {
        token = token == null ? null : token.strip();
    }
}
