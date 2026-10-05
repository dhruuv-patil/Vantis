package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * An external OAuth2 identity (Google, GitHub) linked to a {@link UserAccount}.
 * Mapped to {@code oauth_accounts}; the schema is owned by Flyway ({@code V17__oauth_accounts.sql}).
 */
@Entity
@Table(name = "oauth_accounts")
public class OAuthAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OAuthAccount() {
        // JPA
    }

    public static OAuthAccount create(UUID userId, String provider, String providerUserId, String email) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerUserId, "providerUserId must not be null");

        OAuthAccount account = new OAuthAccount();
        account.id = UUID.randomUUID();
        account.userId = userId;
        account.provider = provider.toLowerCase(Locale.ROOT).trim();
        account.providerUserId = providerUserId.trim();
        account.email = email != null ? email.toLowerCase(Locale.ROOT).trim() : null;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getEmail() {
        return email;
    }

    public void updateEmail(String email) {
        this.email = email != null ? email.toLowerCase(Locale.ROOT).trim() : null;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "OAuthAccount{id=" + id + ", userId=" + userId + ", provider='" + provider + "'}";
    }
}
