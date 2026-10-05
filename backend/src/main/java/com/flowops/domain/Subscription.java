package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Authoritative workspace subscription record.
 * Holds plan, status, regional currency, billing country, and period bounds.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private UUID workspaceId;

    @Column(name = "plan", nullable = false, length = 32)
    private String plan;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "billing_country", nullable = false, length = 2)
    private String billingCountry;

    @Column(name = "billing_interval", nullable = false, length = 16)
    private String billingInterval;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "provider_customer_id")
    private String providerCustomerId;

    @Column(name = "provider_subscription_id")
    private String providerSubscriptionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
        // JPA
    }

    public static Subscription create(
            UUID workspaceId,
            String plan,
            String status,
            String currency,
            String billingCountry,
            String billingInterval,
            Instant currentPeriodStart,
            Instant currentPeriodEnd) {
        Subscription sub = new Subscription();
        sub.id = UUID.randomUUID();
        sub.workspaceId = workspaceId;
        sub.plan = plan;
        sub.status = status;
        sub.currency = currency;
        sub.billingCountry = billingCountry;
        sub.billingInterval = billingInterval;
        sub.currentPeriodStart = currentPeriodStart;
        sub.currentPeriodEnd = currentPeriodEnd;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        sub.createdAt = now;
        sub.updatedAt = now;
        return sub;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getBillingCountry() {
        return billingCountry;
    }

    public void setBillingCountry(String billingCountry) {
        this.billingCountry = billingCountry;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getBillingInterval() {
        return billingInterval;
    }

    public void setBillingInterval(String billingInterval) {
        this.billingInterval = billingInterval;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public void setCurrentPeriodStart(Instant currentPeriodStart) {
        this.currentPeriodStart = currentPeriodStart;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(Instant currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public String getProviderCustomerId() {
        return providerCustomerId;
    }

    public void setProviderCustomerId(String providerCustomerId) {
        this.providerCustomerId = providerCustomerId;
    }

    public String getProviderSubscriptionId() {
        return providerSubscriptionId;
    }

    public void setProviderSubscriptionId(String providerSubscriptionId) {
        this.providerSubscriptionId = providerSubscriptionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
