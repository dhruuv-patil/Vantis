package com.flowops.repository;

import com.flowops.domain.ExecutionStatus;
import com.flowops.domain.WorkflowExecution;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Executions, always fetched together with their owning organization id: there is
 * no bare {@code findById} for callers, so cross-tenant reads are impossible by
 * construction (contract §2 rule 6).
 */
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    /** Shared predicate to exclude quota/rate rejections from all reliability metrics, dashboards, and alerts. */
    String NOT_REJECTED = "e.rejectionReason IS NULL";

    Optional<WorkflowExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Every non-terminal execution across every org, used by the startup recovery
     * sweep. A run is non-terminal while it is {@code QUEUED} (never started),
     * {@code RUNNING} (the worker was alive and died), or {@code WAITING} (parked
     * for a human decision; the engine leaves these alone but the sweep still
     * reports them for honesty). Returns a list of ids ordered by {@code createdAt}
     * so re-enqueue order matches queue arrival order.
     */
    @Query("""
            SELECT e.id FROM WorkflowExecution e
            WHERE e.status IN (com.flowops.domain.ExecutionStatus.QUEUED,
                               com.flowops.domain.ExecutionStatus.RUNNING)
            ORDER BY e.createdAt ASC
            """)
    List<UUID> findRecoverableExecutionIds();

    /**
     * Org-scoped list with optional {@code workflowId} and {@code status} filters
     * (nulls disable the respective clause), newest first. The caller passes a
     * {@link Pageable} to cap the page size.
     */
    @Query("""
            SELECT e FROM WorkflowExecution e
            WHERE e.organizationId = :organizationId
              AND (:workflowId IS NULL OR e.workflowId = :workflowId)
              AND (:status IS NULL OR e.status = :status)
            ORDER BY e.createdAt DESC
            """)
    List<WorkflowExecution> search(
            @Param("organizationId") UUID organizationId,
            @Param("workflowId") UUID workflowId,
            @Param("status") ExecutionStatus status,
            Pageable pageable);

    /**
     * Lightweight projection over a time window for the dashboard: just the fields
     * the KPI and time-series computation needs, so a wide range does not haul full
     * trigger payloads into memory.
     */
    @Query("SELECT e.status AS status, " +
            "e.createdAt AS createdAt, " +
            "e.startedAt AS startedAt, " +
            "e.finishedAt AS finishedAt " +
            "FROM WorkflowExecution e " +
            "WHERE e.organizationId = :organizationId " +
            "AND e.createdAt >= :since " +
            "AND " + NOT_REJECTED)
    List<ExecutionStatRow> statsSince(
            @Param("organizationId") UUID organizationId, @Param("since") Instant since);

    /** Count all executions for a workflow (excluding quota/rate rejections). */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.workflowId = :workflowId AND " + NOT_REJECTED)
    long countByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Org-scoped count for a workflow (a workflowId from another tenant yields 0). */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.organizationId = :organizationId AND e.workflowId = :workflowId AND " + NOT_REJECTED)
    long countByOrganizationIdAndWorkflowId(
            @Param("organizationId") UUID organizationId,
            @Param("workflowId") UUID workflowId);

    /** Count succeeded executions for a workflow. */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.workflowId = :workflowId AND e.status = com.flowops.domain.ExecutionStatus.SUCCEEDED AND " + NOT_REJECTED)
    long countSucceededByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Org-scoped count of succeeded executions for a workflow. */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.organizationId = :organizationId AND e.workflowId = :workflowId AND e.status = com.flowops.domain.ExecutionStatus.SUCCEEDED AND " + NOT_REJECTED)
    long countSucceededByOrganizationIdAndWorkflowId(
            @Param("organizationId") UUID organizationId,
            @Param("workflowId") UUID workflowId);

    /** Count failed executions for a workflow (excluding quota/rate rejections). */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.workflowId = :workflowId AND e.status = com.flowops.domain.ExecutionStatus.FAILED AND " + NOT_REJECTED)
    long countFailedByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Org-scoped count of failed executions for a workflow (excluding quota/rate rejections). */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.organizationId = :organizationId AND e.workflowId = :workflowId AND e.status = com.flowops.domain.ExecutionStatus.FAILED AND " + NOT_REJECTED)
    long countFailedByOrganizationIdAndWorkflowId(
            @Param("organizationId") UUID organizationId,
            @Param("workflowId") UUID workflowId);

    /** Most recent execution for a workflow in an org (for health widget, excluding rejections). */
    @Query("SELECT e FROM WorkflowExecution e WHERE e.workflowId = :workflowId AND e.organizationId = :organizationId AND " + NOT_REJECTED + " ORDER BY e.finishedAt DESC")
    Optional<WorkflowExecution> findTopByWorkflowIdAndOrganizationIdOrderByFinishedAtDesc(
            @Param("workflowId") UUID workflowId, @Param("organizationId") UUID organizationId);

    /** Count executions in an organization within the billing period (excluding rejections). */
    @Query("SELECT COUNT(e) FROM WorkflowExecution e " +
            "WHERE e.organizationId = :organizationId " +
            "AND e.createdAt >= :periodStart " +
            "AND e.createdAt < :periodEnd " +
            "AND " + NOT_REJECTED)
    long countByOrganizationIdAndPeriod(
            @Param("organizationId") UUID organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    /** Find RUNNING executions whose worker heartbeat has not updated since threshold. */
    @Query("""
            SELECT e.id FROM WorkflowExecution e
            WHERE e.status = com.flowops.domain.ExecutionStatus.RUNNING
              AND (e.lastHeartbeatAt IS NULL OR e.lastHeartbeatAt < :staleThreshold)
            ORDER BY e.createdAt ASC
            """)
    List<UUID> findStaleExecutionIds(@Param("staleThreshold") Instant staleThreshold);

    /** Count active (QUEUED or RUNNING) executions for a tenant. */
    @Query("""
            SELECT COUNT(e) FROM WorkflowExecution e
            WHERE e.organizationId = :organizationId
              AND e.status IN (com.flowops.domain.ExecutionStatus.QUEUED, com.flowops.domain.ExecutionStatus.RUNNING)
            """)
    long countActiveByOrganizationId(@Param("organizationId") UUID organizationId);

    /** Count active (QUEUED or RUNNING) executions for a workflow. */
    @Query("""
            SELECT COUNT(e) FROM WorkflowExecution e
            WHERE e.workflowId = :workflowId
              AND e.status IN (com.flowops.domain.ExecutionStatus.QUEUED, com.flowops.domain.ExecutionStatus.RUNNING)
            """)
    long countActiveByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Most recent rejected execution for an organization to store suppressed rejection counts. */
    Optional<WorkflowExecution> findTopByOrganizationIdAndRejectionReasonIsNotNullOrderByCreatedAtDesc(UUID organizationId);

    /** Executions older than retention cutoff that have not yet been locked. */
    @Query("""
            SELECT e FROM WorkflowExecution e
            WHERE e.organizationId = :organizationId
              AND e.createdAt < :cutoff
              AND e.isLocked = false
            ORDER BY e.createdAt ASC
            """)
    List<WorkflowExecution> findUnlockedOlderThan(
            @Param("organizationId") UUID organizationId,
            @Param("cutoff") Instant cutoff);

    /** Lock executions older than cutoff for an organization (idempotent). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            UPDATE WorkflowExecution e
            SET e.isLocked = true, e.lockedAt = :now, e.updatedAt = :now
            WHERE e.organizationId = :organizationId
              AND e.createdAt < :cutoff
              AND e.isLocked = false
            """)
    int lockExecutionsOlderThan(
            @Param("organizationId") UUID organizationId,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);

    /** Global sweep to lock executions older than cutoff across all organizations (idempotent). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            UPDATE WorkflowExecution e
            SET e.isLocked = true, e.lockedAt = :now, e.updatedAt = :now
            WHERE e.createdAt < :cutoff
              AND e.isLocked = false
            """)
    int lockExecutionsOlderThanGlobal(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);
}