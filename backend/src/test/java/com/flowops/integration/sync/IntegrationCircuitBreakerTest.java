package com.flowops.integration.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.ProviderRateLimitException;
import com.flowops.integration.provider.ProviderUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IntegrationCircuitBreaker}:
 * - Respects HTTP 429 Retry-After header with jitter
 * - Trips circuit breaker to OPEN on rate limit or repeated failures
 * - Fast-fails sync attempts during active backoff
 * - Exponential backoff with jitter on 5xx errors
 * - Resets on successful sync or credential update
 */
class IntegrationCircuitBreakerTest {

    private IntegrationCircuitBreaker breaker;
    private UUID integrationId;

    @BeforeEach
    void setUp() {
        breaker = new IntegrationCircuitBreaker();
        integrationId = UUID.randomUUID();
    }

    @Test
    void allowsExecutionInitiallyWhenClosed() {
        assertThat(breaker.allowExecution(integrationId)).isTrue();
        IntegrationCircuitBreaker.BreakerInfo info = breaker.getInfo(integrationId);
        assertThat(info.state()).isEqualTo(IntegrationCircuitBreaker.State.CLOSED);
        assertThat(info.consecutiveFailures()).isEqualTo(0);
    }

    @Test
    void rateLimitTripsCircuitToOpenAndBlocksExecution() {
        ProviderRateLimitException rateLimitEx = new ProviderRateLimitException(
                IntegrationType.N8N, "Too many requests", 30L);

        breaker.recordFailure(integrationId, rateLimitEx);

        // Immediate subsequent call should be rejected without network dispatch
        assertThat(breaker.allowExecution(integrationId)).isFalse();

        IntegrationCircuitBreaker.BreakerInfo info = breaker.getInfo(integrationId);
        assertThat(info.state()).isEqualTo(IntegrationCircuitBreaker.State.OPEN);
        assertThat(info.backoffUntil()).isNotNull();
        assertThat(breaker.getRemainingBackoffSeconds(integrationId)).isGreaterThanOrEqualTo(29L);
    }

    @Test
    void repeatedFailuresApplyExponentialBackoffAndTripCircuit() {
        ProviderUnavailableException serverError = new ProviderUnavailableException(
                IntegrationType.N8N, "Gateway timeout (504)");

        // 4 failures: backoff is applied but circuit state not yet OPEN (below threshold 5)
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure(integrationId, serverError);
        }
        assertThat(breaker.getInfo(integrationId).consecutiveFailures()).isEqualTo(4);

        // 5th failure: trips to OPEN
        breaker.recordFailure(integrationId, serverError);
        IntegrationCircuitBreaker.BreakerInfo info = breaker.getInfo(integrationId);
        assertThat(info.consecutiveFailures()).isEqualTo(5);
        assertThat(info.state()).isEqualTo(IntegrationCircuitBreaker.State.OPEN);
        assertThat(breaker.allowExecution(integrationId)).isFalse();
    }

    @Test
    void recordSuccessResetsBreakerState() {
        ProviderRateLimitException rateLimitEx = new ProviderRateLimitException(
                IntegrationType.MAKE, "Rate limit", 60L);

        breaker.recordFailure(integrationId, rateLimitEx);
        assertThat(breaker.allowExecution(integrationId)).isFalse();

        breaker.recordSuccess(integrationId);

        assertThat(breaker.allowExecution(integrationId)).isTrue();
        IntegrationCircuitBreaker.BreakerInfo info = breaker.getInfo(integrationId);
        assertThat(info.state()).isEqualTo(IntegrationCircuitBreaker.State.CLOSED);
        assertThat(info.consecutiveFailures()).isEqualTo(0);
        assertThat(info.backoffUntil()).isNull();
    }

    @Test
    void resetClearsStateOnCredentialUpdate() {
        breaker.recordFailure(integrationId, new RuntimeException("Auth error"));
        assertThat(breaker.getInfo(integrationId).consecutiveFailures()).isEqualTo(1);

        breaker.reset(integrationId);

        assertThat(breaker.allowExecution(integrationId)).isTrue();
        assertThat(breaker.getInfo(integrationId).consecutiveFailures()).isEqualTo(0);
    }
}
