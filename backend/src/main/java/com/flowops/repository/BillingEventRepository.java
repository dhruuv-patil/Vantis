package com.flowops.repository;

import com.flowops.domain.BillingEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingEventRepository extends JpaRepository<BillingEvent, UUID> {

    Optional<BillingEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    List<BillingEvent> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
