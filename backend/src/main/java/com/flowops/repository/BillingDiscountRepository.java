package com.flowops.repository;

import com.flowops.domain.BillingDiscount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingDiscountRepository extends JpaRepository<BillingDiscount, UUID> {

    Optional<BillingDiscount> findByOrganizationIdAndCode(UUID organizationId, String code);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    List<BillingDiscount> findByOrganizationId(UUID organizationId);
}
