package com.flowops.repository;

import com.flowops.domain.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Idempotency key store for safe replay of webhook calls.
 * Keys are scoped by workflow to prevent cross-workflow collisions.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /** Find by composite key (e.g., "webhook:{workflowId}:{callerKey}"). */
    Optional<IdempotencyKey> findByKey(@Param("key") String key);

    /** Clean up expired keys periodically. */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /**
     * Atomically claims an idempotency key via INSERT ... ON CONFLICT DO NOTHING.
     * Returns 1 if claimed by this transaction, 0 if already claimed by a concurrent winner.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (id, idempotency_key, execution_id, expires_at, created_at) " +
                   "VALUES (:id, :key, :executionId, :expiresAt, :createdAt) " +
                   "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfNotExists(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("executionId") UUID executionId,
            @Param("expiresAt") Instant expiresAt,
            @Param("createdAt") Instant createdAt);
}