package com.flowops.repository;

import com.flowops.domain.AiUsageEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, UUID> {

    @Query("""
            SELECT COALESCE(SUM(e.quantity), 0)
            FROM AiUsageEvent e
            WHERE e.organizationId = :organizationId
              AND e.createdAt >= :periodStart
              AND e.createdAt < :periodEnd
            """)
    long countUsageByOrganizationIdAndPeriod(
            @Param("organizationId") UUID organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Query("""
            SELECT COALESCE(SUM(e.quantity), 0)
            FROM AiUsageEvent e
            WHERE e.organizationId = :organizationId
              AND e.eventType = :eventType
              AND e.createdAt >= :periodStart
              AND e.createdAt < :periodEnd
            """)
    long countUsageByOrganizationIdAndEventTypeAndPeriod(
            @Param("organizationId") UUID organizationId,
            @Param("eventType") String eventType,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);
}
