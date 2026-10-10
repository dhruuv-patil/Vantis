package com.flowops.repository;

import com.flowops.domain.Integration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * All reads are org-scoped by construction: there is no bare {@code findById} exposed
 * to callers, so an integration can only be fetched together with its owning
 * organization id (contract §2 rule 6). The one un-scoped lookup —
 * {@link #findByOrganizationIdAndTypeAndStatus} — still takes the org id explicitly;
 * it is what makes the execution engine's Slack-credential resolution tenant-safe.
 */
public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    Optional<Integration> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Integration> findByOrganizationIdOrderByUpdatedAtDesc(UUID organizationId);

    Optional<Integration> findByOrganizationIdAndType(UUID organizationId, String type);

    Optional<Integration> findByOrganizationIdAndTypeAndName(UUID organizationId, String type, String name);

    List<Integration> findAllByOrganizationIdAndType(UUID organizationId, String type);

    Optional<Integration> findByOrganizationIdAndTypeAndStatus(
            UUID organizationId, String type, String status);

    /** All integrations of a status in one org (event dispatch, sync). */
    List<Integration> findByOrganizationIdAndStatus(UUID organizationId, String status);

    /** Count connected integrations in an org for billing limits. */
    long countByOrganizationIdAndStatus(UUID organizationId, String status);

    /** All connected, non-disabled integrations (for the scheduler). */
    List<Integration> findByStatus(String status);

    List<Integration> findByStatusAndDisabledAtIsNull(String status);
}
