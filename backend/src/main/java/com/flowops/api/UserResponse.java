package com.flowops.api;

import com.flowops.domain.UserAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for a user (contract §4.1).
 *
 * <p>Built field-by-field from the entity, never by reflection — that is what
 * keeps {@code passwordHash} out of every response, log line and OpenAPI schema.
 * {@code avatarUrl} is always {@code null} in M1; clients render initials from
 * {@code fullName}.
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String avatarUrl,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse of(UserAccount user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getAvatarUrl(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
