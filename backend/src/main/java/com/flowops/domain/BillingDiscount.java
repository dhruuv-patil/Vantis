package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Tracks applied billing discounts (such as the 30% founding member offer).
 * Uniqueness of (organization_id, code) is enforced at the database level.
 */
@Entity
@Table(name = "billing_discounts")
public class BillingDiscount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "discount_pct", nullable = false)
    private int discountPct;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "provider_coupon_id", length = 255)
    private String providerCouponId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillingDiscount() {
        // JPA
    }

    public static BillingDiscount create(
            UUID organizationId,
            String code,
            int discountPct,
            Instant startsAt,
            Instant endsAt,
            String providerCouponId) {
        BillingDiscount discount = new BillingDiscount();
        discount.id = UUID.randomUUID();
        discount.organizationId = organizationId;
        discount.code = code;
        discount.discountPct = discountPct;
        discount.startsAt = startsAt;
        discount.endsAt = endsAt;
        discount.providerCouponId = providerCouponId;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        discount.createdAt = now;
        discount.updatedAt = now;
        return discount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public int getDiscountPct() {
        return discountPct;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getProviderCouponId() {
        return providerCouponId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
