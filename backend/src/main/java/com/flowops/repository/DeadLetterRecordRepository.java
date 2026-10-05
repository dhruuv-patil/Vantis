package com.flowops.repository;

import com.flowops.domain.DeadLetterRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecord, UUID> {

    Optional<DeadLetterRecord> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<DeadLetterRecord> findByExecutionId(UUID executionId);

    @Query("""
        SELECT d FROM DeadLetterRecord d
        WHERE d.organizationId = :organizationId
          AND (:workflowId IS NULL OR d.workflowId = :workflowId)
          AND (:status IS NULL OR d.status = :status)
        ORDER BY d.createdAt DESC
    """)
    List<DeadLetterRecord> search(
            @Param("organizationId") UUID organizationId,
            @Param("workflowId") UUID workflowId,
            @Param("status") String status,
            Pageable pageable);

    long countByOrganizationIdAndStatus(UUID organizationId, String status);
}
