package com.flowops.repository;

import com.flowops.domain.BillingNotice;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingNoticeRepository extends JpaRepository<BillingNotice, UUID> {

    Optional<BillingNotice> findByOrganizationIdAndPeriodStartAndThresholdAndKind(
            UUID organizationId,
            Instant periodStart,
            int threshold,
            String kind);
}
