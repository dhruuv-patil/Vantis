package com.flowops.repository;

import com.flowops.domain.Subscription;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByWorkspaceId(UUID workspaceId);

    Optional<Subscription> findFirstByProviderSubscriptionIdOrderByCreatedAtDesc(String providerSubscriptionId);

    Optional<Subscription> findFirstByProviderCustomerIdOrderByCreatedAtDesc(String providerCustomerId);

    boolean existsByWorkspaceId(UUID workspaceId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM Subscription s
            WHERE s.plan = 'PRIVATE_BETA'
              AND s.betaEndsAt IS NOT NULL
              AND s.betaEndsAt <= :cutoff
            """)
    java.util.List<Subscription> findExpiredBetaSubscriptions(
            @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM Subscription s
            WHERE s.plan = 'PRIVATE_BETA'
              AND s.status = 'ACTIVE'
              AND s.betaStartedAt IS NOT NULL
              AND s.betaEndsAt IS NOT NULL
            """)
    java.util.List<Subscription> findActiveBetaSubscriptions();

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM Subscription s
            WHERE s.status = 'PAST_DUE'
            """)
    java.util.List<Subscription> findPastDueSubscriptions();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            UPDATE Subscription s
            SET s.plan = 'FREE', s.status = 'ACTIVE', s.updatedAt = :now
            WHERE s.id = :id AND s.plan = 'PRIVATE_BETA'
            """)
    int downgradeExpiredBeta(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            UPDATE Subscription s
            SET s.plan = 'FREE', s.status = 'ACTIVE', s.updatedAt = :now
            WHERE s.id = :id AND s.status = 'PAST_DUE'
            """)
    int downgradePastDue(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
