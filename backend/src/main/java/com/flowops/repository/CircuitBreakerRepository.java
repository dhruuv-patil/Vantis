package com.flowops.repository;

import com.flowops.domain.CircuitBreaker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CircuitBreakerRepository extends JpaRepository<CircuitBreaker, UUID> {

    Optional<CircuitBreaker> findByOrganizationIdAndTargetDependency(
            UUID organizationId, String targetDependency);

    List<CircuitBreaker> findByOrganizationId(UUID organizationId);
}
