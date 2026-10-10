package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Sync state for a provider integration.
 * One row per integration, updated by the sync scheduler.
 */
@Entity
@Table(name = "integration_sync_state")
public class IntegrationSyncState {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "integration_id", nullable = false, updatable = false, unique = true)
    private UUID integrationId;

    @Column(name = "cursor", length = 1000)
    private String cursor;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "last_error_category", length = 50)
    private String lastErrorCategory;

    @Column(name = "health_status", nullable = false, length = 50)
    private String healthStatus = "NEW";

    @Column(name = "is_backfilling", nullable = false)
    private boolean isBackfilling = false;

    @Column(name = "backfill_progress", nullable = false)
    private int backfillProgress = 100;

    @Column(name = "backfill_cursor", length = 1000)
    private String backfillCursor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntegrationSyncState() {
        // JPA
    }

    public static IntegrationSyncState create(
            UUID organizationId,
            UUID integrationId) {

        IntegrationSyncState state = new IntegrationSyncState();
        state.id = UUID.randomUUID();
        state.organizationId = organizationId;
        state.integrationId = integrationId;
        state.status = "NEW";
        state.healthStatus = "NEW";
        state.attempt = 0;
        state.isBackfilling = false;
        state.backfillProgress = 100;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        state.createdAt = now;
        state.updatedAt = now;
        return state;
    }

    private void touch() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public void updateCursor(String cursor) {
        this.cursor = cursor;
        touch();
    }

    public void markSynced() {
        this.lastSyncedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastSuccessAt = this.lastSyncedAt;
        this.lastSuccessfulSyncAt = this.lastSyncedAt;
        this.status = "HEALTHY";
        this.healthStatus = "HEALTHY";
        this.lastError = null;
        this.lastErrorCategory = null;
        this.attempt = 0;
        touch();
    }

    public void markFailed(String error) {
        markFailed(error, "SYNC_ERROR");
    }

    public void markFailed(String error, String errorCategory) {
        this.lastSyncedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastErrorAt = this.lastSyncedAt;
        this.lastError = error;
        this.lastErrorCategory = errorCategory;
        this.status = "FAILED";
        this.healthStatus = "DEGRADED";
        this.attempt = this.attempt + 1;
        touch();
    }

    public void markRateLimited(long retryAfterSeconds, String error) {
        this.lastSyncedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastErrorAt = this.lastSyncedAt;
        this.lastError = error;
        this.lastErrorCategory = "RATE_LIMIT";
        this.status = "FAILED";
        this.healthStatus = "RATE_LIMITED";
        this.attempt = this.attempt + 1;
        touch();
    }

    public void markAuthFailed(String error) {
        this.lastSyncedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastErrorAt = this.lastSyncedAt;
        this.lastError = error;
        this.lastErrorCategory = "AUTH_ERROR";
        this.status = "FAILED";
        this.healthStatus = "AUTH_FAILED";
        touch();
    }

    public void markUnreachable(String error) {
        this.lastSyncedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.lastErrorAt = this.lastSyncedAt;
        this.lastError = error;
        this.lastErrorCategory = "UNREACHABLE";
        this.status = "FAILED";
        this.healthStatus = "UNREACHABLE";
        this.attempt = this.attempt + 1;
        touch();
    }

    public void markStalled() {
        this.healthStatus = "SYNC_STALLED";
        this.lastErrorCategory = "STALLED";
        touch();
    }

    public void startBackfill() {
        this.isBackfilling = true;
        this.backfillProgress = 0;
        this.healthStatus = "BACKFILLING";
        touch();
    }

    public void updateBackfill(int progress, String cursor) {
        this.backfillProgress = progress;
        this.backfillCursor = cursor;
        touch();
    }

    public void completeBackfill() {
        this.isBackfilling = false;
        this.backfillProgress = 100;
        this.backfillCursor = null;
        if ("BACKFILLING".equals(this.healthStatus)) {
            this.healthStatus = "HEALTHY";
        }
        touch();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIntegrationId() {
        return integrationId;
    }

    public String getCursor() {
        return cursor;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt != null ? lastSuccessfulSyncAt : lastSuccessAt;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastErrorCategory() {
        return lastErrorCategory;
    }

    public String getStatus() {
        return status;
    }

    public String getHealthStatus() {
        return healthStatus != null ? healthStatus : status;
    }

    public boolean isBackfilling() {
        return isBackfilling;
    }

    public int getBackfillProgress() {
        return backfillProgress;
    }

    public String getBackfillCursor() {
        return backfillCursor;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}