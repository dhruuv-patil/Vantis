package com.flowops.billing.service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled background execution of subscription lifecycle jobs:
 * - Beta expiry downgrades
 * - Beta expiry warning notifications
 * - Dunning grace period checks and downgrades
 * - Execution history retention locking
 *
 * Distributed execution safety is guaranteed via Redis distributed locks:
 * - Unique token generated per scheduler invocation.
 * - Lock acquired via atomic SET NX EX.
 * - Lock released strictly via atomic Lua compare-and-delete (prevents old owner deleting new owner's lock).
 * - Safe failure mode: If Redis is unavailable or fails, sweep is SKIPPED to prevent concurrent execution.
 */
@Service
public class BillingLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingLifecycleScheduler.class);

    public static final String LOCK_KEY = "lock:billing:lifecycle-sweep";
    public static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final String LUA_RELEASE_LOCK = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(LUA_RELEASE_LOCK, Long.class);

    private final SubscriptionLifecycleService lifecycleService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public BillingLifecycleScheduler(
            SubscriptionLifecycleService lifecycleService,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.lifecycleService = lifecycleService;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(
            fixedDelayString = "${flowops.billing.lifecycle.sweep-delay:1h}",
            initialDelayString = "${flowops.billing.lifecycle.sweep-initial-delay:1m}")
    public void runLifecycleSweep() {
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = false;

        if (redisTemplate != null) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, lockToken, LOCK_TTL);
                if (acquired == null || !acquired) {
                    log.debug("Lifecycle sweep lock already held by another instance. Skipping.");
                    return;
                }
                lockAcquired = true;
            } catch (Exception ex) {
                // Safer behavior: lifecycle correctness is prioritized over forcing sweep during temporary Redis outage
                log.warn("Redis unavailable for lifecycle sweep lock. Skipping sweep until Redis recovers: {}", ex.getMessage());
                return;
            }
        }

        long startMs = System.currentTimeMillis();
        log.info("LIFECYCLE_SWEEP_START: token={}", lockToken);

        try {
            int betaDowngrades = lifecycleService.expireBetaSubscriptions();
            int noticesSent = lifecycleService.sendBetaExpiryWarnings();
            int dunningDowngrades = lifecycleService.processDunningAndGracePeriods();
            int executionsLocked = lifecycleService.lockExpiredExecutionHistory();

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("LIFECYCLE_SWEEP_COMPLETED: betaDowngrades={} noticesSent={} dunningDowngrades={} executionsLocked={} elapsedMs={}",
                    betaDowngrades, noticesSent, dunningDowngrades, executionsLocked, elapsedMs);
        } catch (Exception ex) {
            log.error("LIFECYCLE_SWEEP_ERROR: error during scheduled sweep: {}", ex.getMessage(), ex);
        } finally {
            if (lockAcquired && redisTemplate != null) {
                releaseLock(lockToken);
            }
        }
    }

    /**
     * Atomically releases the distributed lock only if the current value in Redis matches our token.
     */
    public boolean releaseLock(String lockToken) {
        if (redisTemplate == null || lockToken == null) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    Collections.singletonList(LOCK_KEY),
                    lockToken);
            boolean released = result != null && result > 0;
            if (released) {
                log.debug("Successfully released distributed lock for token={}", lockToken);
            } else {
                log.warn("Lock token mismatch or lock expired; did not release lock for token={}", lockToken);
            }
            return released;
        } catch (Exception ex) {
            log.warn("Failed to execute atomic lock release script for token {}: {}", lockToken, ex.getMessage());
            return false;
        }
    }
}
