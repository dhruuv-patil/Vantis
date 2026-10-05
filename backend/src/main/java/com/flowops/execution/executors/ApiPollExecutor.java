package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.config.ExecutionProperties;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * API Poll: repeatedly requests a URL until a dotted-path condition
 * ({@code stopWhen})
 * resolves to a truthy value in the response body, or {@code maxPolls} attempts
 * have
 * been exhausted. Useful for long-running jobs whose completion flag only
 * appears once
 * the work is done.
 */
@Component
public class ApiPollExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public ApiPollExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "api_poll";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");
        if (url == null || url.isBlank()) {
            return NodeResult.fail("API Poll has no URL configured.");
        }

        URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail("API Poll URL rejected: " + blocked.getMessage());
        }

        String stopWhen = ctx.configString("stopWhen");
        if (stopWhen == null || stopWhen.isBlank()) {
            return NodeResult.fail("API Poll has no stopWhen path configured.");
        }

        String method = orDefault(ctx.configString("method"), "GET").toUpperCase(Locale.ROOT);
        int maxPolls = parseInt(ctx.configString("maxPolls"), 10);
        int pollIntervalSeconds = parseInt(ctx.configString("pollIntervalSeconds"), 5);

        JsonNode lastBody = null;
        int statusCode = 0;

        for (int poll = 1; poll <= maxPolls; poll++) {
            String body = ctx.configString("body");
            HttpRequest.BodyPublisher publisher = (body == null || body.isEmpty())
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(ctx.properties().httpRequestTimeout())
                    .method(method, publisher);
            applyHeaders(ctx, request);

            long startedAt = System.nanoTime();
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            statusCode = response.statusCode();
            lastBody = parseBody(ctx, response);
            ctx.log().info("API Poll " + safeTarget(uri) + " #" + poll + " → " + statusCode + " (" + millis + "ms)");

            if (isTruthy(resolvePath(lastBody, stopWhen))) {
                ctx.log().info("API Poll " + safeTarget(uri) + " stop condition met at poll #" + poll + ".");
                return NodeResult.success(buildPollOutput(ctx, statusCode, lastBody, poll, true));
            }

            if (poll < maxPolls) {
                Thread.sleep(Duration.ofSeconds(pollIntervalSeconds));
            }
        }

        ctx.log().warn("API Poll " + safeTarget(uri) + " exhausted " + maxPolls + " polls without stop condition.");
        return NodeResult.success(buildPollOutput(ctx, statusCode, lastBody, maxPolls, false));
    }

    private ObjectNode buildPollOutput(NodeExecutionContext ctx, int status, JsonNode body, int pollsUsed,
            boolean stoppedByCondition) {
        ObjectNode output = ctx.mapper().createObjectNode();
        output.put("status", status);
        output.put("pollsUsed", pollsUsed);
        output.put("stoppedByCondition", stoppedByCondition);
        output.set("body", body == null ? ctx.mapper().createObjectNode() : body);
        return output;
    }

    private void applyHeaders(NodeExecutionContext ctx, HttpRequest.Builder request) {
        Object rawHeaders = ctx.config().get("headers");
        Map<String, Object> headers = asMap(ctx, rawHeaders);
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String value = ctx.interpolate(String.valueOf(entry.getValue()));
            try {
                request.header(entry.getKey(), value);
            } catch (IllegalArgumentException restricted) {
                ctx.log().warn("Skipped restricted request header \"" + entry.getKey() + "\".");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(NodeExecutionContext ctx, Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                JsonNode parsed = ctx.mapper().readTree(text);
                if (parsed.isObject()) {
                    return ctx.mapper().convertValue(parsed, Map.class);
                }
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    private JsonNode parseBody(NodeExecutionContext ctx, HttpResponse<String> response) {
        String raw = response.body() == null ? "" : response.body();
        int cap = ctx.properties().httpMaxBodyBytes();
        boolean truncated = raw.length() > cap;
        String captured = truncated ? raw.substring(0, cap) : raw;

        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!truncated && contentType.toLowerCase(Locale.ROOT).contains("json") && !captured.isBlank()) {
            try {
                return ctx.mapper().readTree(captured);
            } catch (Exception notJson) {
            }
        }
        if (truncated) {
            return ctx.mapper().getNodeFactory().textNode(captured);
        }
        return ctx.mapper().getNodeFactory().textNode(captured);
    }

    /**
     * Walks a dotted path (e.g. {@code result.done}) against a JSON node; missing
     * if any segment is absent.
     */
    private JsonNode resolvePath(JsonNode root, String path) {
        if (root == null || root.isMissingNode() || path == null || path.isBlank()) {
            return MissingNode.getInstance();
        }
        JsonNode current = root;
        for (String rawSegment : path.trim().split("\\.")) {
            String segment = rawSegment.trim();
            if (segment.isEmpty() || current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            if (current.isArray()) {
                try {
                    current = current.path(Integer.parseInt(segment));
                } catch (NumberFormatException notAnIndex) {
                    return MissingNode.getInstance();
                }
            } else {
                current = current.path(segment);
            }
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    private boolean isTruthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isValueNode()) {
            String text = node.asText();
            return text != null && !text.isEmpty()
                    && !text.equalsIgnoreCase("false")
                    && !text.equals("0");
        }
        return true;
    }

    /** Host + path only in logs — never the query string. */
    private String safeTarget(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return uri.getScheme() + "://" + host + path;
    }
}