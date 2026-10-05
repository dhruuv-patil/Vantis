package com.flowops.repository;

import com.flowops.domain.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByOrganizationIdAndIdempotencyKey(
            UUID organizationId, String idempotencyKey);

    Optional<IdempotencyRecord> findByExecutionIdAndNodeIdAndAttempt(
            UUID executionId, String nodeId, int attempt);
}
