package com.flowops.auth;

/**
 * {@code POST /api/auth/register} response.
 *
 * <p>Returned upon successful account creation. No session, access token, or
 * refresh cookie is issued until email verification has completed.
 */
public record RegisterResponse(
        String email,
        boolean emailVerified,
        String message) {
}
