package com.flowops.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * {@code POST /api/auth/resend-verification} request body.
 */
public record ResendVerificationRequest(
        @NotBlank(message = "Email is required.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        String email) {

    public ResendVerificationRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
