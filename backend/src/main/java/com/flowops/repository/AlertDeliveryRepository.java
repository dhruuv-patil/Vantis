package com.flowops.repository;

import com.flowops.domain.AlertDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertDeliveryRepository extends JpaRepository<AlertDelivery, UUID> {

    Optional<AlertDelivery> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
        SELECT a FROM AlertDelivery a
        WHERE a.organizationId = :organizationId
          AND (:channel IS NULL OR a.channel = :channel)
          AND (:status IS NULL OR a.status = :status)
        ORDER BY a.createdAt DESC
    """)
    List<AlertDelivery> search(
            @Param("organizationId") UUID organizationId,
            @Param("channel") String channel,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
        SELECT a FROM AlertDelivery a
        WHERE a.organizationId = :organizationId
          AND a.dedupKey = :dedupKey
          AND a.createdAt > :since
        ORDER BY a.createdAt DESC
    """)
    List<AlertDelivery> findRecentByDedupKey(
            @Param("organizationId") UUID organizationId,
            @Param("dedupKey") String dedupKey,
            @Param("since") Instant since);
}
