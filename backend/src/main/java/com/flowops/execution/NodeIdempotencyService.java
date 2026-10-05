package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.domain.IdempotencyRecord;
import com.flowops.repository.IdempotencyRecordRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable persistence for node-level external side-effect idempotency.
 * Ensures external operations (HTTP mutations, emails, webhooks, payments)
 * are not duplicated during retries or replays.
 */
@Service
public class NodeIdempotencyService {

    private final IdempotencyRecordRepository repository;

    public NodeIdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks if an external side effect with this key was already completed.
     * Returns the cached response payload if available.
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findCompleted(UUID organizationId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey)
                .filter(rec -> "COMPLETED".equals(rec.getEffectStatus()));
    }

    /**
     * Checks if this exact attempt of this node in this run already has a record.
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findByRunAndNode(UUID executionId, String nodeId, int attempt) {
        return repository.findByExecutionIdAndNodeIdAndAttempt(executionId, nodeId, attempt);
    }

    /**
     * Records the start of a side-effecting attempt.
     */
    @Transactional
    public IdempotencyRecord recordStart(
            UUID organizationId,
            UUID executionId,
            String nodeId,
            String idempotencyKey,
            int attempt) {
        return repository.findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey)
                .orElseGet(() -> repository.save(
                        IdempotencyRecord.start(organizationId, executionId, nodeId, idempotencyKey, attempt)));
    }

    /**
     * Records successful completion of a side effect with its captured response.
     */
    @Transactional
    public void recordSuccess(UUID recordId, JsonNode responsePayload) {
        repository.findById(recordId).ifPresent(rec -> {
            rec.complete(responsePayload);
            repository.save(rec);
        });
    }

    /**
     * Records failure of a side effect.
     */
    @Transactional
    public void recordFailure(UUID recordId, JsonNode errorPayload) {
        repository.findById(recordId).ifPresent(rec -> {
            rec.fail(errorPayload);
            repository.save(rec);
        });
    }

    /**
     * Check or set an idempotency key (used by IdempotencyCheckExecutor with durable persistence).
     */
    @Transactional
    public boolean checkAndSet(UUID organizationId, UUID executionId, String nodeId, String key) {
        Optional<IdempotencyRecord> existing = repository.findByOrganizationIdAndIdempotencyKey(organizationId, key);
        if (existing.isPresent()) {
            return true; // Already processed / duplicate
        }
        IdempotencyRecord record = IdempotencyRecord.start(organizationId, executionId, nodeId, key, 1);
        record.complete(null);
        repository.save(record);
        return false; // First time seen
    }
}
