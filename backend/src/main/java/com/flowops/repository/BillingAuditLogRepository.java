package com.flowops.repository;

import com.flowops.domain.BillingAuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingAuditLogRepository extends JpaRepository<BillingAuditLog, UUID> {

    List<BillingAuditLog> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
