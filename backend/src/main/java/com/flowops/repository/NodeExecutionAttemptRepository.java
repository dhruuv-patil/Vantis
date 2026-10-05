package com.flowops.repository;

import com.flowops.domain.NodeExecutionAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NodeExecutionAttemptRepository extends JpaRepository<NodeExecutionAttempt, UUID> {

    List<NodeExecutionAttempt> findByExecutionIdOrderByStartedAtAsc(UUID executionId);

    List<NodeExecutionAttempt> findByExecutionIdAndNodeIdOrderByAttemptNumberAsc(UUID executionId, String nodeId);

    Optional<NodeExecutionAttempt> findByExecutionIdAndNodeIdAndAttemptNumber(
            UUID executionId, String nodeId, int attemptNumber);

    long countByExecutionIdAndNodeId(UUID executionId, String nodeId);
}
