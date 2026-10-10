package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A single-use email verification token. Only the SHA-256 hex is persisted;
 * the plaintext exists only in the email link sent to the user.
 * Mapped to {@code email_verification_tokens} ({@code V25__email_verification.sql}).
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected EmailVerificationToken() {
        // JPA
    }

    public static EmailVerificationToken create(UUID userId, String tokenHash, Instant expiresAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        token.createdAt = now;
        token.usedAt = null;
        return token;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isConsumed() {
        return usedAt != null;
    }

    public void consume() {
        this.usedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
