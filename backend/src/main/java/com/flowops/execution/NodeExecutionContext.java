package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.config.ExecutionProperties;
import com.flowops.workflow.graph.GraphNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@link NodeExecutor} is handed for one node run: the graph node and
 * its config, the resolved variable root for {@code {{...}}} interpolation, shared
 * helpers, the engine's tuning limits, and a logger.
 *
 * <p>Executors read from here and return a {@link NodeResult}; they never touch the
 * database, the SSE stream, or other nodes directly. That keeps each executor a
 * pure function of (config, variables) — easy to reason about and impossible to use
 * as a lateral channel between tenants.
 *
 * <p>{@code config()} returns the node's <em>effective</em> config: usually just
 * {@code node.config()}, but for a node that references a stored resource (an
 * {@code ai_agent} pointing at a saved agent) the engine resolves that reference at
 * its own DB boundary and hands the merged result in here. The executor still only
 * sees plain config values — the tenant-scoped lookup already happened upstream.
 *
 * <p>{@code secret(key)} exposes a decrypted credential (e.g. a Slack webhook URL)
 * on a channel deliberately separate from {@code config()}: the engine snapshots
 * config to the database, so a secret must never travel that way. Secrets ride this
 * non-persisted map only, are read by the executor at delivery time, and are never
 * logged or emitted.
 */
public final class NodeExecutionContext {

    private final GraphNode node;
    private final Map<String, Object> effectiveConfig;
    private final Map<String, String> secrets;
    private final JsonNode variables;
    private final VariableInterpolator interpolator;
    private final ObjectMapper mapper;
    private final ExecutionProperties properties;
    private final NodeLogger logger;
    private final java.util.UUID organizationId;
    private final java.util.UUID executionId;
    private final int attempt;
    private final NodeIdempotencyService idempotencyService;
    private final com.flowops.reliability.CircuitBreakerService circuitBreakerService;

    public NodeExecutionContext(
            GraphNode node,
            Map<String, Object> effectiveConfig,
            Map<String, String> secrets,
            JsonNode variables,
            VariableInterpolator interpolator,
            ObjectMapper mapper,
            ExecutionProperties properties,
            NodeLogger logger) {
        this(node, effectiveConfig, secrets, variables, interpolator, mapper, properties, logger, null, null, 1, null, null);
    }

    public NodeExecutionContext(
            GraphNode node,
            Map<String, Object> effectiveConfig,
            Map<String, String> secrets,
            JsonNode variables,
            VariableInterpolator interpolator,
            ObjectMapper mapper,
            ExecutionProperties properties,
            NodeLogger logger,
            java.util.UUID organizationId,
            java.util.UUID executionId,
            int attempt,
            NodeIdempotencyService idempotencyService,
            com.flowops.reliability.CircuitBreakerService circuitBreakerService) {
        this.node = node;
        this.effectiveConfig = effectiveConfig == null ? Map.of() : effectiveConfig;
        this.secrets = secrets == null ? Map.of() : secrets;
        this.variables = variables;
        this.interpolator = interpolator;
        this.mapper = mapper;
        this.properties = properties;
        this.logger = logger;
        this.organizationId = organizationId;
        this.executionId = executionId;
        this.attempt = attempt;
        this.idempotencyService = idempotencyService;
        this.circuitBreakerService = circuitBreakerService;
    }

    public GraphNode node() {
        return node;
    }

    public Map<String, Object> config() {
        return effectiveConfig;
    }

    /**
     * A decrypted secret for this node (e.g. {@code "slackWebhookUrl"}), or null when
     * none is available. Resolved by the engine from an encrypted stored credential
     * and delivered only through this non-persisted channel — never through
     * {@link #config()}, which the engine snapshots to the database. Executors must
     * never log or emit the returned value.
     */
    public String secret(String key) {
        return secrets.get(key);
    }

    /** The variable root: {@code trigger} plus every succeeded node's output. */
    public JsonNode variables() {
        return variables;
    }

    /** Replaces {@code {{ path }}} tokens in {@code template} with resolved values. */
    public String interpolate(String template) {
        return interpolator.interpolate(template, variables);
    }

    /** Resolves a single dotted path to a JSON node (missing if absent). */
    public JsonNode resolve(String path) {
        return interpolator.resolve(variables, path);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ExecutionProperties properties() {
        return properties;
    }

    public NodeLogger log() {
        return logger;
    }

    /** Reads a config value as a trimmed string, interpolating it; null-safe. */
    public String configString(String key) {
        Object raw = config().get(key);
        return raw == null ? null : interpolate(String.valueOf(raw));
    }

    /**
     * Reads a config value as a list of strings (e.g. allowlisted tool names). A
     * missing or non-list value yields an empty list. Values are not interpolated —
     * these are identifiers, not templates.
     */
    public List<String> configStringList(String key) {
        Object raw = config().get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    public java.util.UUID organizationId() {
        return organizationId;
    }

    public java.util.UUID executionId() {
        return executionId;
    }

    public int attempt() {
        return attempt;
    }

    public NodeIdempotencyService idempotencyService() {
        return idempotencyService;
    }

    public com.flowops.reliability.CircuitBreakerService circuitBreakerService() {
        return circuitBreakerService;
    }
}
