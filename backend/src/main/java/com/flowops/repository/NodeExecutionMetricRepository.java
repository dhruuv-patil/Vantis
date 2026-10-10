package com.flowops.repository;

import com.flowops.domain.NodeExecutionMetric;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NodeExecutionMetricRepository
        extends JpaRepository<NodeExecutionMetric, UUID> {

    List<NodeExecutionMetric> findByExecutionId(UUID executionId);

    Optional<NodeExecutionMetric> findByExecutionIdAndNodeId(
            UUID executionId,
            String nodeId);

    void deleteByExecutionId(UUID executionId);

    List<NodeExecutionMetric>
    findByOrganizationIdAndWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(
            UUID organizationId,
            UUID workflowId,
            Instant after);

    List<NodeExecutionMetric>
    findByWorkflowIdAndNodeIdAndStartedAtAfterOrderByStartedAtAsc(
            UUID workflowId,
            String nodeId,
            Instant after);

    List<NodeExecutionMetric>
    findByWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(
            UUID workflowId,
            Instant after);

    Optional<NodeExecutionMetric>
    findByExecutionIdAndNodeIdAndOrganizationId(
            UUID executionId,
            String nodeId,
            UUID organizationId);

    @Query("""
        select distinct m.workflowId
        from NodeExecutionMetric m
        where m.startedAt >= :after
        """)
    List<UUID> findDistinctWorkflowIdsSince(Instant after);

    @Query("""
        select count(distinct m.executionId)
        from NodeExecutionMetric m
        where m.workflowId = :workflowId
          and m.startedAt >= :after
        """)
    long countDistinctExecutionsSince(
            UUID workflowId,
            Instant after);

    List<NodeExecutionMetric> findTop30ByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

    long countByWorkflowIdAndStatusAndStartedAtAfter(
            UUID workflowId,
            com.flowops.domain.NodeRunStatus status,
            Instant after);
}