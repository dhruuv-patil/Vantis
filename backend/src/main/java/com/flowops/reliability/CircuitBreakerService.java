package com.flowops.reliability;

import com.flowops.domain.CircuitBreaker;
import com.flowops.repository.CircuitBreakerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages circuit breaker state across external dependencies to prevent cascading failures.
 * Supports CLOSED, OPEN, HALF_OPEN states with persistent tracking per tenant and dependency.
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_RECOVERY_TIMEOUT_MS = 30_000L; // 30 seconds

    private final CircuitBreakerRepository repository;

    public CircuitBreakerService(CircuitBreakerRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks whether an execution node is allowed to call an external dependency.
     * Returns true if CLOSED or eligible for HALF_OPEN probe; false if OPEN.
     */
    @Transactional
    public boolean allowRequest(UUID organizationId, String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return true;
        }
        String normalized = dependency.trim().toLowerCase();
        CircuitBreaker cb = repository.findByOrganizationIdAndTargetDependency(organizationId, normalized)
                .orElse(null);

        if (cb == null) {
            return true;
        }

        boolean allowed = cb.allowRequest();
        repository.save(cb);
        return allowed;
    }

    /**
     * Records a successful call to the external dependency.
     */
    @Transactional
    public void recordSuccess(UUID organizationId, String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return;
        }
        String normalized = dependency.trim().toLowerCase();
        repository.findByOrganizationIdAndTargetDependency(organizationId, normalized)
                .ifPresent(cb -> {
                    cb.recordSuccess();
                    repository.save(cb);
                });
    }

    /**
     * Records a failed call to the external dependency.
     */
    @Transactional
    public void recordFailure(UUID organizationId, String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return;
        }
        String normalized = dependency.trim().toLowerCase();
        CircuitBreaker cb = repository.findByOrganizationIdAndTargetDependency(organizationId, normalized)
                .orElseGet(() -> CircuitBreaker.create(
                        organizationId,
                        normalized,
                        DEFAULT_FAILURE_THRESHOLD,
                        DEFAULT_RECOVERY_TIMEOUT_MS));

        cb.recordFailure();
        repository.save(cb);
        if (CircuitBreaker.STATE_OPEN.equals(cb.getState())) {
            log.warn("Circuit breaker OPEN for dependency '{}' in organization {}", normalized, organizationId);
        }
    }

    @Transactional(readOnly = true)
    public List<CircuitBreaker> listForOrganization(UUID organizationId) {
        return repository.findByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public Optional<CircuitBreaker> get(UUID organizationId, String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return Optional.empty();
        }
        return repository.findByOrganizationIdAndTargetDependency(organizationId, dependency.trim().toLowerCase());
    }
}
