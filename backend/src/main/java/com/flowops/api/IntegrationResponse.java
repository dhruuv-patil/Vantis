package com.flowops.api;

import com.flowops.domain.Integration;
import java.time.Instant;
import java.util.UUID;

/**
 * An org's integration as returned to the client. Carries only non-secret metadata:
 * the encrypted credential is <em>never</em> included — {@code hint} is the masked
 * last-4 of the secret (null when disconnected), safe to display.
 */
public record IntegrationResponse(
        UUID id,
        String type,
        String name,
        String status,
        String hint,
        Instant disabledAt,
        boolean disabled,
        Instant createdAt,
        Instant updatedAt) {

    /** Backwards-compatible 7-argument constructor. */
    public IntegrationResponse(
            UUID id,
            String type,
            String name,
            String status,
            String hint,
            Instant createdAt,
            Instant updatedAt) {
        this(id, type, name, status, hint, null, false, createdAt, updatedAt);
    }

    public static IntegrationResponse of(Integration integration) {
        return new IntegrationResponse(
                integration.getId(),
                integration.getType(),
                integration.getName(),
                integration.getStatus(),
                integration.getHint(),
                integration.getDisabledAt(),
                integration.isDisabled(),
                integration.getCreatedAt(),
                integration.getUpdatedAt());
    }
}
