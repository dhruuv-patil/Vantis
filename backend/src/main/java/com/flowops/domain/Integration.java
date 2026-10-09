package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An organization's connection to an external provider.
 *
 * <p>The secret itself is never held here — it lives AES-256-GCM encrypted in
 * {@link IntegrationCredential}. This row carries only non-secret metadata: the
 * provider {@code type}, a display {@code name}, the {@code status}, a masked
 * {@code hint} (last-4 of the secret) safe to show in the UI, and optional
 * provider-specific {@code metadata} (e.g. baseUrl for n8n). Foreign keys are plain
 * UUIDs (contract §2 rule 6) so every query stays explicitly org-scoped.
 */
@Entity
@Table(name = "integrations")
public class Integration {

    /** The only provider wired end-to-end in M5. Kept for backward compatibility. */
    public static final String TYPE_SLACK = "slack";

    /** Connection lifecycle. A disconnected integration keeps no credential. */
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISCONNECTED = "disconnected";
    public static final String STATUS_AUTH_ERROR = "auth_error";
    public static final String STATUS_ERROR = "error";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "hint", length = 24)
    private String hint;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Integration() {
        // JPA
    }

    /** Creates a freshly-connected integration with its display hint set. */
    public static Integration createConnected(
            UUID organizationId, UUID createdBy, String type, String name, String hint) {
        Integration integration = new Integration();
        integration.id = UUID.randomUUID();
        integration.organizationId = organizationId;
        integration.createdBy = createdBy;
        integration.type = type;
        integration.name = name;
        integration.status = STATUS_CONNECTED;
        integration.hint = hint;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        integration.createdAt = now;
        integration.updatedAt = now;
        return integration;
    }

    private void touch() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Marks (or re-marks) this integration connected, refreshing its display hint. */
    public void connect(String name, String hint) {
        this.name = name;
        this.hint = hint;
        this.status = STATUS_CONNECTED;
        touch();
    }

    /** Marks this integration disconnected and clears the display hint. */
    public void disconnect() {
        this.status = STATUS_DISCONNECTED;
        this.hint = null;
        touch();
    }

    /**
     * Transitions to {@code auth_error}: the stored credentials were rejected by the
     * provider (401/403). The integration stays connected until an ADMIN fixes the
     * credentials. Only auth failures take this path — a temporary 5xx never does.
     */
    public void markAuthError() {
        this.status = STATUS_AUTH_ERROR;
        touch();
    }

    public boolean isConnected() {
        return STATUS_CONNECTED.equals(status);
    }

    /** Soft-disables this integration, pausing monitoring without deleting credentials. */
    public void disable() {
        this.disabledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        touch();
    }

    /** Re-enables a soft-disabled integration. */
    public void enable() {
        this.disabledAt = null;
        touch();
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getHint() {
        return hint;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        touch();
    }
}
