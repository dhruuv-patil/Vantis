package com.flowops.execution;

import com.flowops.config.ExecutionProperties;
import com.flowops.workflow.graph.GraphNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses and evaluates per-node retry policies and error classifications.
 * Distinguishes retryable errors (timeouts, 502, 503, 504, 429, network disconnects)
 * from non-retryable errors (400, 401, 403, 404, 422, validation rejections)
 * and computes exponential backoff with jitter.
 */
@Component
public class RetryPolicyClassifier {

    private static final Set<Integer> NON_RETRYABLE_STATUS_CODES = Set.of(
            400, 401, 403, 404, 405, 409, 410, 413, 415, 422);

    private static final List<Pattern> NON_RETRYABLE_PATTERNS = List.of(
            Pattern.compile(".*(400|401|403|404|405|409|410|413|415|422).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(unauthorized|forbidden|not found|bad request|invalid credentials|validation error).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(syntax error|invalid syntax|malformed|unsupported media).*", Pattern.CASE_INSENSITIVE));

    public record NodeRetryPolicy(
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff,
            boolean jitter,
            Duration timeout) {
    }

    public NodeRetryPolicy resolvePolicy(GraphNode node, ExecutionProperties properties) {
        Map<String, Object> config = node.config();
        int maxAttempts = properties.maxAttempts();
        Duration baseBackoff = properties.retryBackoff();
        Duration maxBackoff = properties.maxRetryBackoff();
        boolean jitter = true;
        Duration timeout = properties.nodeTimeout();

        if (config != null) {
            Object rawAttempts = config.get("maxAttempts");
            if (rawAttempts instanceof Number num) {
                maxAttempts = Math.max(1, Math.min(10, num.intValue()));
            } else if (rawAttempts instanceof String str && !str.isBlank()) {
                try {
                    maxAttempts = Math.max(1, Math.min(10, Integer.parseInt(str.trim())));
                } catch (NumberFormatException ignored) {}
            }

            Object rawBackoff = config.get("backoffSeconds");
            if (rawBackoff instanceof Number num) {
                baseBackoff = Duration.ofSeconds(Math.max(1, num.longValue()));
            }

            Object rawJitter = config.get("jitter");
            if (rawJitter instanceof Boolean b) {
                jitter = b;
            }

            Object rawTimeout = config.get("timeoutSeconds");
            if (rawTimeout instanceof Number num) {
                timeout = Duration.ofSeconds(Math.max(1, Math.min(600, num.longValue())));
            }
        }

        return new NodeRetryPolicy(maxAttempts, baseBackoff, maxBackoff, jitter, timeout);
    }

    /**
     * Determines whether an error is retryable.
     * Non-retryable errors stop retry loops immediately to conserve retry budget.
     */
    public boolean isRetryable(String error, Integer httpStatus) {
        if (httpStatus != null && NON_RETRYABLE_STATUS_CODES.contains(httpStatus)) {
            return false;
        }

        if (error == null || error.isBlank()) {
            return true;
        }

        for (Pattern pattern : NON_RETRYABLE_PATTERNS) {
            if (pattern.matcher(error).matches()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates backoff duration with exponential scaling and full jitter.
     */
    public long calculateBackoffMillis(int attempt, NodeRetryPolicy policy) {
        long base = policy.baseBackoff().toMillis();
        long scaled = base * (1L << Math.min(10, attempt - 1));
        long capped = Math.min(scaled, policy.maxBackoff().toMillis());

        if (!policy.jitter()) {
            return capped;
        }

        // Full jitter: between 50% and 120% of capped backoff
        double factor = 0.5 + 0.7 * ThreadLocalRandom.current().nextDouble();
        return Math.max(100L, (long) (capped * factor));
    }
}
