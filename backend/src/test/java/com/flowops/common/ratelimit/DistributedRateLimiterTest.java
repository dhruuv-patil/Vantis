package com.flowops.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Focused test suite for Redis-backed distributed rate limiting.
 *
 * <p>Covers:
 * <ol>
 *   <li>Requests below configured limit are accepted.</li>
 *   <li>Requests exceeding limit are rejected with 429 RATE_LIMITED and retryAfter.</li>
 *   <li>Counters expire correctly and reset when window elapses.</li>
 *   <li>Separate users receive separate limits (isolated counters).</li>
 *   <li>Separate endpoints/actions receive separate limits (isolated counters).</li>
 *   <li>Concurrent requests cannot exceed the configured limit (atomic enforcement).</li>
 *   <li>Redis failure behavior: fail-closed for security-sensitive endpoints,
 *       fail-open for operational endpoints.</li>
 *   <li>Key formatting, casing normalization, and CRLF injection prevention.</li>
 * </ol>
 */
class DistributedRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private MockRedisAtomicEngine redisEngine;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redisEngine = new MockRedisAtomicEngine();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(redisEngine);

        rateLimiter = new RateLimiter(redisTemplate, true, true);
    }

    @Test
    @DisplayName("1. Requests below the configured limit are accepted")
    void requestsBelowConfiguredLimitAreAccepted() {
        int limit = 5;
        Duration window = Duration.ofMinutes(1);

        for (int i = 0; i < limit; i++) {
            rateLimiter.check("user:123", limit, window);
        }

        // All 5 calls succeeded without throwing
    }

    @Test
    @DisplayName("2. The request exceeding the limit is rejected with 429 and retryAfter")
    void requestExceedingLimitIsRejected() {
        int limit = 3;
        Duration window = Duration.ofSeconds(30);

        for (int i = 0; i < limit; i++) {
            rateLimiter.check("action:key", limit, window);
        }

        assertThatThrownBy(() -> rateLimiter.check("action:key", limit, window))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(apiEx.retryAfterSeconds()).isNotNull();
                    assertThat(apiEx.retryAfterSeconds()).isGreaterThan(0);
                    assertThat(apiEx.getMessage()).contains("Too many attempts");
                });
    }

    @Test
    @DisplayName("3. Counters expire correctly and reset after window")
    void countersExpireCorrectly() {
        int limit = 2;
        Duration window = Duration.ofMillis(50);

        rateLimiter.check("expiring:key", limit, window);
        rateLimiter.check("expiring:key", limit, window);

        // Exhausted
        assertThatThrownBy(() -> rateLimiter.check("expiring:key", limit, window))
                .isInstanceOf(ApiException.class);

        // Expire key in Redis
        redisEngine.expireKey(RateLimiter.formatKey("expiring:key"));

        // Count has reset, next call is allowed
        rateLimiter.check("expiring:key", limit, window);
    }

    @Test
    @DisplayName("4. Separate users receive separate limits")
    void separateUsersReceiveSeparateLimits() {
        int limit = 2;
        Duration window = Duration.ofMinutes(1);

        rateLimiter.check("user:alice", limit, window);
        rateLimiter.check("user:alice", limit, window);

        // Alice is exhausted
        assertThatThrownBy(() -> rateLimiter.check("user:alice", limit, window))
                .isInstanceOf(ApiException.class);

        // Bob has his own separate allowance
        rateLimiter.check("user:bob", limit, window);
        rateLimiter.check("user:bob", limit, window);

        // Now Bob is also exhausted
        assertThatThrownBy(() -> rateLimiter.check("user:bob", limit, window))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("5. Separate endpoints/actions receive separate limits")
    void separateEndpointsReceiveSeparateLimits() {
        int limit = 2;
        Duration window = Duration.ofMinutes(1);

        String user = "user-uuid-123";
        rateLimiter.check("ai:generate:" + user, limit, window);
        rateLimiter.check("ai:generate:" + user, limit, window);

        // ai:generate is exhausted for user
        assertThatThrownBy(() -> rateLimiter.check("ai:generate:" + user, limit, window))
                .isInstanceOf(ApiException.class);

        // workflow:run is a separate endpoint action with its own quota
        rateLimiter.check("workflow:run:" + user, limit, window);
        rateLimiter.check("workflow:run:" + user, limit, window);
        assertThatThrownBy(() -> rateLimiter.check("workflow:run:" + user, limit, window))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("6. Concurrent requests cannot exceed the configured limit")
    void concurrentRequestsCannotExceedConfiguredLimit() throws Exception {
        int limit = 10;
        int totalRequests = 50;
        Duration window = Duration.ofMinutes(1);
        String key = "concurrent:test:user";

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    rateLimiter.check(key, limit, window);
                    allowedCount.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.code() == ErrorCode.RATE_LIMITED) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(allowedCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(totalRequests - limit);
    }

    @Test
    @DisplayName("7a. Redis failure fails closed for security-sensitive endpoints")
    void redisFailureFailsClosedForSecuritySensitiveEndpoints() {
        redisEngine.setFailRedis(true);

        List<String> securityKeys = List.of(
                "login:192.168.1.1:user@example.com",
                "register:ip:10.0.0.1",
                "forgot-password:ip:10.0.0.1",
                "forgot-password:10.0.0.1:user@example.com",
                "reset-password:ip:10.0.0.1",
                "verify-email:ip:10.0.0.1",
                "resend-verification:ip:10.0.0.1",
                "refresh:ip:10.0.0.1",
                "invite-accept:user:user-uuid-456"
        );

        for (String key : securityKeys) {
            assertThatThrownBy(() -> rateLimiter.check(key, 5, Duration.ofMinutes(15)))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.RATE_LIMITED);
        }
    }

    @Test
    @DisplayName("7b. Redis failure fails open for operational endpoints")
    void redisFailureFailsOpenForOperationalEndpoints() {
        redisEngine.setFailRedis(true);

        List<String> operationalKeys = List.of(
                "webhook:workflow-uuid-1",
                "ai:generate:user-uuid-1",
                "ai:investigate:user-uuid-1",
                "workflow:run:user-uuid-1",
                "create-org:user:user-uuid-1"
        );

        for (String key : operationalKeys) {
            // Must not throw — fails open to preserve data ingestion and workflow availability
            rateLimiter.check(key, 10, Duration.ofMinutes(1));
        }
    }

    @Test
    @DisplayName("8. Key formatting: CRLF sanitization, lowercase normalization, ratelimit prefix")
    void keyFormattingAndSanitization() {
        assertThat(RateLimiter.formatKey("login:1.2.3.4:User@Example.COM"))
                .isEqualTo("ratelimit:login:1.2.3.4:user@example.com");

        assertThat(RateLimiter.formatKey("login:1.2.3.4\r\nINJECT"))
                .isEqualTo("ratelimit:login:1.2.3.4inject");

        assertThat(RateLimiter.formatKey("ratelimit:already:prefixed"))
                .isEqualTo("ratelimit:already:prefixed");

        assertThat(RateLimiter.formatKey(null))
                .isEqualTo("ratelimit:unknown");

        assertThat(RateLimiter.formatKey("   "))
                .isEqualTo("ratelimit:unknown");
    }

    @Test
    @DisplayName("9. Casing variations map to the same rate-limit counter")
    void casingVariationsShareSameCounter() {
        int limit = 1;
        Duration window = Duration.ofMinutes(1);

        rateLimiter.check("login:127.0.0.1:Alice@Example.com", limit, window);

        // Different casing of the same email trips the limit
        assertThatThrownBy(() ->
                rateLimiter.check("login:127.0.0.1:alice@example.COM", limit, window))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("10. Disabled limiter is a no-op even with Redis wired")
    void disabledLimiterIsNoOpWithRedis() {
        RateLimiter disabledLimiter = new RateLimiter(redisTemplate, false, true);

        for (int i = 0; i < 50; i++) {
            disabledLimiter.check("test:key", 1, Duration.ofMinutes(1));
        }
    }

    @Test
    @DisplayName("11. Separate client IPs receive isolated rate-limit counters")
    void separateClientIpsReceiveIsolatedLimits() {
        int limit = 5;
        Duration window = Duration.ofMinutes(60);

        // IP 1 uses all 5 allowances
        for (int i = 0; i < limit; i++) {
            rateLimiter.check("register:ip:203.0.113.10", limit, window);
        }

        // IP 1 is rate limited
        assertThatThrownBy(() -> rateLimiter.check("register:ip:203.0.113.10", limit, window))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        // IP 2 is completely unaffected and has its own full quota
        for (int i = 0; i < limit; i++) {
            rateLimiter.check("register:ip:203.0.113.20", limit, window);
        }
        assertThatThrownBy(() -> rateLimiter.check("register:ip:203.0.113.20", limit, window))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("12. Retry-after duration accurately reflects remaining window time")
    void retryAfterAccuratelyReflectsRemainingWindow() {
        int limit = 1;
        Duration window = Duration.ofMinutes(25);

        rateLimiter.check("action:retry-check", limit, window);

        assertThatThrownBy(() -> rateLimiter.check("action:retry-check", limit, window))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(apiEx.retryAfterSeconds()).isNotNull();
                    // Remaining time should be at most 25 minutes (1500 seconds) and positive
                    assertThat(apiEx.retryAfterSeconds()).isBetween(1L, 1500L);
                    assertThat(apiEx.getMessage()).contains(String.valueOf(apiEx.retryAfterSeconds()));
                });
    }

    /**
     * In-memory simulator of the Redis Lua script execution.
     */
    private static class MockRedisAtomicEngine implements Answer<Long> {
        private final Map<String, Long> counters = new ConcurrentHashMap<>();
        private final Map<String, Long> expiries = new ConcurrentHashMap<>();
        private final AtomicBoolean failRedis = new AtomicBoolean(false);

        public void setFailRedis(boolean fail) {
            this.failRedis.set(fail);
        }

        public void expireKey(String key) {
            counters.remove(key);
            expiries.remove(key);
        }

        @Override
        public synchronized Long answer(InvocationOnMock invocation) {
            if (failRedis.get()) {
                throw new RedisConnectionFailureException("Simulated Redis connection outage");
            }

            List<String> keys = invocation.getArgument(1);
            String key = keys.get(0);
            long limit = Long.parseLong(invocation.getArgument(2).toString());
            long windowMillis = Long.parseLong(invocation.getArgument(3).toString());

            long now = System.currentTimeMillis();
            Long expiry = expiries.get(key);
            if (expiry != null && now >= expiry) {
                counters.remove(key);
                expiries.remove(key);
            }

            long current = counters.merge(key, 1L, Long::sum);
            if (current == 1L) {
                expiries.put(key, now + windowMillis);
            } else if (!expiries.containsKey(key)) {
                expiries.put(key, now + windowMillis);
            }

            if (current > limit) {
                long pttl = Math.max(0, expiries.get(key) - now);
                long remaining = Math.max(1, (pttl + 999) / 1000);
                return remaining;
            }

            return 0L;
        }
    }
}
