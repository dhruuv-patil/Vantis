package com.flowops.repository;

import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * All reads are org-scoped by construction: there is no bare {@code findById}
 * exposed to callers, so a workflow can only ever be fetched together with its
 * owning organization id (contract §2 rule 6).
 */
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Optional<Workflow> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Finds a workflow by exact name and organization (for external workflow mapping). */
    Optional<Workflow> findByNameAndOrganizationId(String name, UUID organizationId);

    /**
     * Listing with an optional case-insensitive name search and optional status
     * filter. Nulls disable the respective clause. Archived rows are included
     * only when explicitly filtered to {@code ARCHIVED}.
     *
     * <p>The {@code CAST(:search AS string)} is load-bearing, not cosmetic: when
     * {@code :search} is null Hibernate has no value to infer the type from, so
     * PostgreSQL sees an untyped bind and resolves {@code LOWER(...)} against
     * {@code bytea} ("function lower(bytea) does not exist"). The explicit cast
     * pins the parameter to {@code varchar} so {@code LOWER} always type-checks.
     */
    @Query("""
            SELECT w FROM Workflow w
            WHERE w.organizationId = :organizationId
              AND (:search IS NULL OR LOWER(w.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:status IS NULL OR w.status = :status)
              AND (:status IS NOT NULL OR w.status <> com.flowops.domain.WorkflowStatus.ARCHIVED)
            ORDER BY w.updatedAt DESC
            """)
    List<Workflow> search(
            @Param("organizationId") UUID organizationId,
            @Param("search") String search,
            @Param("status") WorkflowStatus status);

    /**
     * Active workflows count for billing quota calculations.
     * Defined as all non-archived workflows (DRAFT and PUBLISHED) belonging to the organization.
     * ARCHIVED workflows are excluded from the quota count.
     */
    @Query("""
            SELECT COUNT(w) FROM Workflow w
            WHERE w.organizationId = :organizationId
              AND w.status <> com.flowops.domain.WorkflowStatus.ARCHIVED
            """)
    long countActiveByOrganizationId(@Param("organizationId") UUID organizationId);
}

