package com.flowops.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * {@code POST /api/auth/register} body (contract §5.1, §7.1).
 *
 * <p>Normalization happens in the compact constructor because Jackson invokes the
 * canonical constructor during deserialization — so trimming completes
 * <em>before</em> Bean Validation inspects the values. Without that ordering
 * {@code "  Ab  "} would satisfy a {@code min = 2} check that the trimmed value
 * fails.
 *
 * <p>There is no {@code role} and no {@code organizationId} component. The role is
 * hardcoded {@code OWNER} server-side, and {@code fail-on-unknown-properties}
 * turns an attempt to smuggle either one into {@code 400 MALFORMED_REQUEST}.
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        @Pattern(
                regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                message = "Enter a valid email address.")
        String email,

        @NotBlank(message = "Password is required.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$",
                message =
                        "Password must be 8–72 characters and include at least one letter"
                                + " and one number.")
        String password,

        @NotBlank(message = "Full name is required.")
        @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters.")
        String fullName,

        @Size(min = 2, max = 80, message = "Organization name must be between 2 and 80 characters.")
        String organizationName) {

    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        fullName = fullName == null ? null : fullName.strip();
        if ((organizationName == null || organizationName.isBlank()) && fullName != null && !fullName.isBlank()) {
            organizationName = fullName + "'s Workspace";
        } else {
            organizationName = organizationName == null ? null : organizationName.strip();
        }
        // password is intentionally NOT trimmed — leading/trailing whitespace is
        // a legitimate part of a passphrase.
    }
}
