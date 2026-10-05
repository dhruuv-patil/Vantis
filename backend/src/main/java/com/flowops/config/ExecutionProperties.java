package com.flowops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code flowops.execution} tree from {@code application.yml}: the sizing
 * and safety limits of the in-process execution engine.
 *
 * <p>Every value here is a guardrail. The delay cap stops a mis-configured Delay
 * node from parking a worker for hours; the HTTP timeouts and body cap stop a slow
 * or hostile endpoint from exhausting the pool or memory; the retry settings bound
 * how long a flaky node is retried before the run is failed honestly.
 *
 * @param workerPoolSize   threads that run executions concurrently (in-memory queue)
 * @param maxAttempts      total attempts per node before it fails (1 = no retry)
 * @param retryBackoff     base backoff, doubled each attempt (exponential)
 * @param maxRetryBackoff  ceiling for a single backoff wait
 * @param maxDelaySeconds  the largest a Delay node may actually sleep
 * @param httpConnectTimeout connect timeout for the HTTP Request node
 * @param httpRequestTimeout overall request timeout for the HTTP Request node
 * @param httpMaxBodyBytes maximum captured response body; larger bodies are truncated
 * @param sseTimeout       how long a live execution SSE stream stays open
 */
@ConfigurationProperties(prefix = "flowops.execution")
public record ExecutionProperties(
        @DefaultValue("4") int workerPoolSize,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue("30s") Duration maxRetryBackoff,
        @DefaultValue("300") long maxDelaySeconds,
        @DefaultValue("5s") Duration httpConnectTimeout,
        @DefaultValue("30s") Duration httpRequestTimeout,
        @DefaultValue("1048576") int httpMaxBodyBytes,
        @DefaultValue("30m") Duration sseTimeout,
        @DefaultValue("5m") Duration staleThreshold,
        @DefaultValue("10s") Duration heartbeatInterval,
        @DefaultValue("10") int maxTenantConcurrency,
        @DefaultValue("5") int maxWorkflowConcurrency,
        @DefaultValue("1000") int maxStepsPerExecution,
        @DefaultValue("60s") Duration nodeTimeout,
        @DefaultValue("3600s") Duration workflowTimeout) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ExecutionProperties {
        if (workerPoolSize < 1) {
            workerPoolSize = 1;
        }
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (maxTenantConcurrency < 1) {
            maxTenantConcurrency = 1;
        }
        if (maxWorkflowConcurrency < 1) {
            maxWorkflowConcurrency = 1;
        }
        if (maxStepsPerExecution < 1) {
            maxStepsPerExecution = 100;
        }
    }

    public ExecutionProperties() {
        this(
                4,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                300L,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                1048576,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                10,
                5,
                1000,
                Duration.ofSeconds(60),
                Duration.ofSeconds(3600));
    }

    public ExecutionProperties(
            int workerPoolSize,
            int maxAttempts,
            Duration retryBackoff,
            Duration maxRetryBackoff,
            long maxDelaySeconds,
            Duration httpConnectTimeout,
            Duration httpRequestTimeout,
            int httpMaxBodyBytes,
            Duration sseTimeout) {
        this(
                workerPoolSize,
                maxAttempts,
                retryBackoff,
                maxRetryBackoff,
                maxDelaySeconds,
                httpConnectTimeout,
                httpRequestTimeout,
                httpMaxBodyBytes,
                sseTimeout,
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                10,
                5,
                1000,
                Duration.ofSeconds(60),
                Duration.ofSeconds(3600));
    }

    public static ExecutionProperties defaults() {
        return new ExecutionProperties(
                4,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                300L,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                1048576,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                10,
                5,
                1000,
                Duration.ofSeconds(60),
                Duration.ofSeconds(3600));
    }
}
