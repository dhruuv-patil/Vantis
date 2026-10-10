package com.flowops.repository;

import com.flowops.domain.Incident;
import com.flowops.domain.IncidentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByIdAndOrganizationId(
            UUID id,
            UUID organizationId);

    List<Incident> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId);

    List<Incident> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId,
            IncidentStatus status);

    List<Incident> findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(
            UUID organizationId,
            UUID workflowId);

    List<Incident> findByOrganizationIdAndAnomalyId(
            UUID organizationId,
            UUID anomalyId);

    Optional<Incident> findFirstByOrganizationIdAndFingerprintAndStatusInOrderByCreatedAtDesc(
            UUID organizationId,
            String fingerprint,
            java.util.Collection<IncidentStatus> statuses);

    Optional<Incident> findFirstByOrganizationIdAndFingerprintOrderByCreatedAtDesc(
            UUID organizationId,
            String fingerprint);

    long countByOrganizationIdAndStatus(
            UUID organizationId,
            IncidentStatus status);
}
