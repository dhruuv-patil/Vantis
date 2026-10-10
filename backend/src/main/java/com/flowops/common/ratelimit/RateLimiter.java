package com.flowops.common.ratelimit;

import com.flowops.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed rate limiter backed by Redis with atomic Lua scripting.
 *
 * <p>When a Redis connection is present, rate-limiting windows and counters are
 * maintained in Redis across all running backend instances. If Redis is unavailable
 * or not configured (e.g. standalone test mode), the limiter safely handles the failure
 * according to endpoint security classification:
 * <ul>
 *   <li>Security-sensitive endpoints (login, register, forgot-password, reset-password, refresh,
 *       invite-accept) fail closed (throwing {@code 429 RATE_LIMITED}) to prevent
 *       brute-force attacks during Redis outages.</li>
 *   <li>Operational/data-plane endpoints (webhooks, workflow runs, AI generation)
 *       fail open to preserve platform availability for ingestion and execution.</li>
 * </ul>
 *
 * <p>Redis keys are namespaced under {@code ratelimit:}, sanitized to prevent CRLF
 * command injection, normalized to prevent casing bypasses, and have explicit TTLs
 * ensuring automatic expiration without unbounded key growth.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public static final String KEY_PREFIX = "ratelimit:";

    private static final Set<String> SECURITY_SENSITIVE_PREFIXES = Set.of(
            "login:",
            "register:",
            "forgot-password:",
            "reset-password:",
            "verify-email:",
            "resend-verification:",
            "refresh:",
            "invite-accept:",
            "billing:checkout:"
    );

    /**
     * Atomic Lua script that implements a fixed-window rate limiter in Redis.
     *
     * <p>KEYS[1]: The rate limit key (e.g. {@code ratelimit:login:127.0.0.1:alice@example.com})
     * <br>ARGV[1]: Max allowed requests in window
     * <br>ARGV[2]: Window size in milliseconds
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code 0}: Within limit, request allowed.</li>
     *   <li>{@code > 0}: Exceeded limit, value is remaining retry-after duration in seconds.</li>
     * </ul>
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])

            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('PEXPIRE', key, windowMillis)
            else
                local pttl = redis.call('PTTL', key)
                if pttl == -1 then
                    redis.call('PEXPIRE', key, windowMillis)
                end
            end

            if current > limit then
                local pttl = redis.call('PTTL', key)
                if pttl < 0 then
                    pttl = windowMillis
                end
                local remaining = math.floor((pttl + 999) / 1000)
                if remaining < 1 then
                    remaining = 1
                end
                return remaining
            end

            return 0
            """;

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final boolean failClosedForSecurity;

    /** In-memory fallback windows when running in standalone mode without Redis. */
    private static final int SWEEP_THRESHOLD = 10_000;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${flowops.ratelimit.enabled:true}") boolean enabled,
            @Value("${flowops.ratelimit.fail-closed-for-security:true}") boolean failClosedForSecurity) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.failClosedForSecurity = failClosedForSecurity;
    }

    public RateLimiter(StringRedisTemplate redisTemplate, boolean enabled) {
        this(redisTemplate, enabled, true);
    }

    public RateLimiter(boolean enabled) {
        this(null, enabled, true);
    }

    /**
     * Counts one hit against {@code key} and throws {@code 429 RATE_LIMITED} once
     * the window's allowance is exhausted. A no-op while {@code enabled} is false.
     */
    public void check(String key, int limit, Duration window) {
        if (!enabled) {
            return;
        }

        String formattedKey = formatKey(key);

        if (redisTemplate != null) {
            checkDistributed(formattedKey, limit, window);
        } else {
            checkLocal(formattedKey, limit, window);
        }
    }

    /**
     * Distributed rate limit check using Redis atomic Lua script.
     */
    private void checkDistributed(String formattedKey, int limit, Duration window) {
        long windowMillis = window.toMillis();
        try {
            Long retryAfterSeconds = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(formattedKey),
                    String.valueOf(limit),
                    String.valueOf(windowMillis)
            );

            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                throw ApiException.rateLimited(retryAfterSeconds);
            }
        } catch (ApiException e) {
            // Re-throw rate limit rejection directly
            throw e;
        } catch (Exception ex) {
            handleRedisFailure(formattedKey, window, ex);
        }
    }

    /**
     * Safe handling for Redis infrastructure failures.
     */
    private void handleRedisFailure(String formattedKey, Duration window, Exception ex) {
        log.error("Redis rate limiter failure for key '{}': {}", formattedKey, ex.getMessage());

        if (failClosedForSecurity && isSecuritySensitive(formattedKey)) {
            // Fail closed: reject request to maintain security boundary on auth/credential flows
            long fallbackRetryAfter = Math.max(1, (window.toMillis() + 999) / 1000);
            throw ApiException.rateLimited(fallbackRetryAfter);
        }

        // Fail open: allow request for operational/data-plane endpoints (e.g. webhooks, workflow runs)
        log.warn("Failing open for non-security key '{}' during Redis failure", formattedKey);
    }

    /**
     * In-memory fixed-window fallback when no RedisTemplate is wired.
     */
    private void checkLocal(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        if (localWindows.size() > SWEEP_THRESHOLD) {
            sweep(now, windowMillis);
        }

        Window counter = localWindows.computeIfAbsent(key, k -> new Window(now));
        synchronized (counter) {
            if (now - counter.startedAt >= windowMillis) {
                counter.startedAt = now;
                counter.count = 0;
            }
            counter.count++;
            if (counter.count > limit) {
                long remaining = windowMillis - (now - counter.startedAt);
                throw ApiException.rateLimited(Math.max(1, (remaining + 999) / 1000));
            }
        }
    }

    /**
     * Checks if the key corresponds to a security-sensitive endpoint.
     */
    public static boolean isSecuritySensitive(String key) {
        if (key == null) {
            return false;
        }
        String clean = key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : key;
        clean = clean.toLowerCase(Locale.ROOT);
        for (String prefix : SECURITY_SENSITIVE_PREFIXES) {
            if (clean.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Formats and sanitizes a rate limit key.
     * Prevents CRLF injection and normalizes casing to prevent bypass tricks.
     */
    public static String formatKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return KEY_PREFIX + "unknown";
        }
        String sanitized = rawKey.replaceAll("[\\r\\n]", "").trim();
        sanitized = sanitized.toLowerCase(Locale.ROOT);
        return sanitized.startsWith(KEY_PREFIX) ? sanitized : KEY_PREFIX + sanitized;
    }

    /** The client address, never a client-supplied forwarding header. */
    public static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private void sweep(long now, long windowMillis) {
        Iterator<Map.Entry<String, Window>> it = localWindows.entrySet().iterator();
        while (it.hasNext()) {
            Window candidate = it.next().getValue();
            synchronized (candidate) {
                if (now - candidate.startedAt >= windowMillis) {
                    it.remove();
                }
            }
        }
    }

    private static final class Window {
        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
