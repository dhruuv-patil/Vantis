package com.flowops.integration.sync;

import com.flowops.integration.provider.ProviderRateLimitException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-integration circuit breaker with exponential backoff and jitter.
 *
 * <p>Protects external provider APIs from repeated calls when rate-limited or failing:
 * <ul>
 *   <li>On HTTP 429: respects {@code Retry-After} with positive jitter and trips circuit to OPEN.</li>
 *   <li>On repeated 5xx/timeouts: computes exponential backoff with jitter up to 5 minutes.</li>
 *   <li>On consecutive failures >= failureThreshold (default 5): opens circuit for cool-down.</li>
 *   <li>Fast-fails synchronization attempts while backoff is active without sending network requests.</li>
 *   <li>Resets on successful synchronization or credential update.</li>
 * </ul>
 */
@Component
public class IntegrationCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCircuitBreaker.class);

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public record BreakerInfo(
            State state,
            int consecutiveFailures,
            Instant backoffUntil,
            String lastReason
    ) {
    }

    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long BASE_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 300_000L; // 5 minutes

    private final Map<UUID, BreakerState> breakers = new ConcurrentHashMap<>();

    private static class BreakerState {
        State state = State.CLOSED;
        int failureCount = 0;
        Instant backoffUntil = null;
        String lastReason = null;
    }

    /**
     * Determines whether a sync operation is permitted for the given integration.
     * Returns false if the circuit is OPEN or currently backed off.
     */
    public boolean allowExecution(UUID integrationId) {
        BreakerState state = breakers.get(integrationId);
        if (state == null) {
            return true;
        }

        Instant now = Instant.now();
        synchronized (state) {
            if (state.backoffUntil != null && now.isBefore(state.backoffUntil)) {
                return false;
            }

            if (state.state == State.OPEN) {
                // Cool-down has elapsed; transition to HALF_OPEN for a single trial
                state.state = State.HALF_OPEN;
                return true;
            }

            return true;
        }
    }

    /**
     * Records a successful synchronization, resetting the circuit breaker.
     */
    public void recordSuccess(UUID integrationId) {
        BreakerState state = breakers.get(integrationId);
        if (state != null) {
            synchronized (state) {
                state.state = State.CLOSED;
                state.failureCount = 0;
                state.backoffUntil = null;
                state.lastReason = null;
            }
        }
    }

    /**
     * Records a rate-limit event (HTTP 429), respecting Retry-After.
     */
    public void recordRateLimit(UUID integrationId, long retryAfterSeconds) {
        BreakerState state = breakers.computeIfAbsent(integrationId, k -> new BreakerState());
        synchronized (state) {
            state.failureCount++;
            state.state = State.OPEN;
            long safeSeconds = Math.max(1, retryAfterSeconds);
            long jitterMs = ThreadLocalRandom.current().nextLong(500, 3000);
            state.backoffUntil = Instant.now().plusSeconds(safeSeconds).plusMillis(jitterMs);
            state.lastReason = "Rate limited (HTTP 429). Retry after " + safeSeconds + "s + jitter";
            log.warn("Circuit breaker OPEN for integration {}: {} (backed off until {})",
                    integrationId, state.lastReason, state.backoffUntil);
        }
    }

    /**
     * Records a synchronization failure, applying exponential backoff with jitter.
     */
    public void recordFailure(UUID integrationId, Throwable error) {
        if (error instanceof ProviderRateLimitException rateLimit) {
            recordRateLimit(integrationId, rateLimit.getRetryAfterSeconds());
            return;
        }

        BreakerState state = breakers.computeIfAbsent(integrationId, k -> new BreakerState());
        synchronized (state) {
            state.failureCount++;
            int shift = Math.min(state.failureCount - 1, 6);
            long delayMs = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << shift));
            long jitterMs = ThreadLocalRandom.current().nextLong(500, 2500);
            state.backoffUntil = Instant.now().plusMillis(delayMs + jitterMs);
            state.lastReason = error != null ? error.getMessage() : "Unknown sync error";

            if (state.failureCount >= DEFAULT_FAILURE_THRESHOLD) {
                state.state = State.OPEN;
                log.warn("Circuit breaker tripped to OPEN for integration {} after {} consecutive failures: {}",
                        integrationId, state.failureCount, state.lastReason);
            } else {
                log.debug("Integration {} failed (count={}): backing off for {}ms",
                        integrationId, state.failureCount, delayMs + jitterMs);
            }
        }
    }

    /**
     * Resets the circuit breaker state (e.g. after credential rotation/reconnect).
     */
    public void reset(UUID integrationId) {
        breakers.remove(integrationId);
    }

    /**
     * Inspects circuit breaker status for diagnostic reporting.
     */
    public BreakerInfo getInfo(UUID integrationId) {
        BreakerState state = breakers.get(integrationId);
        if (state == null) {
            return new BreakerInfo(State.CLOSED, 0, null, null);
        }
        synchronized (state) {
            return new BreakerInfo(state.state, state.failureCount, state.backoffUntil, state.lastReason);
        }
    }

    /**
     * Returns remaining backoff duration in seconds, or 0 if not backed off.
     */
    public long getRemainingBackoffSeconds(UUID integrationId) {
        BreakerState state = breakers.get(integrationId);
        if (state == null || state.backoffUntil == null) {
            return 0;
        }
        Instant now = Instant.now();
        if (now.isAfter(state.backoffUntil)) {
            return 0;
        }
        return Duration.between(now, state.backoffUntil).toSeconds();
    }
}
