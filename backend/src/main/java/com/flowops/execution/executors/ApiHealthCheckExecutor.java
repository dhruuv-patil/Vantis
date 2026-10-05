package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
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
 * API Health Check: probes an endpoint and reports status, latency, and a
 * healthy flag.
 */
@Component
public class ApiHealthCheckExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public ApiHealthCheckExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "api_health_check";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");
        if (url == null || url.isBlank()) {
            return NodeResult.fail("API Health Check has no URL configured.");
        }

        URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail("API Health Check URL is not a valid URI.");
        }

        String method = orDefault(ctx.configString("method"), "GET").toUpperCase(Locale.ROOT);
        int expectedStatus = parseExpectedStatus(ctx);
        Duration timeout = Duration.ofSeconds(parseTimeout(ctx, 10));

        String body = ctx.configString("body");
        HttpRequest.BodyPublisher publisher = (body == null || body.isEmpty())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .method(method, publisher);
        applyHeaders(ctx, request);

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        String error = null;
        int statusCode = 0;

        try {
            response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            statusCode = response.statusCode();
        } catch (Exception e) {
            error = sanitize(e);
        }

        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        boolean healthy = error == null && isStatusHealthy(statusCode, expectedStatus);

        ObjectNode output = ctx.mapper().createObjectNode();
        output.put("url", safeTarget(uri));
        output.put("method", method);
        output.put("status", statusCode);
        output.put("latencyMs", millis);
        output.put("healthy", healthy);

        if (error != null) {
            output.put("error", error);
        }

        ctx.log().info(
                "Health check "
                        + method
                        + " "
                        + safeTarget(uri)
                        + " → "
                        + (healthy ? "healthy" : "unhealthy")
                        + " ("
                        + millis
                        + "ms)");

        return NodeResult.success(output);
    }

    private int parseExpectedStatus(NodeExecutionContext ctx) {
        String raw = ctx.configString("expectedStatus");

        if (raw == null || raw.isBlank()) {
            return -1; // default: any 2xx
        }

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    private int parseTimeout(NodeExecutionContext ctx, int defaultSeconds) {
        String raw = ctx.configString("timeoutSeconds");

        if (raw == null || raw.isBlank()) {
            return defaultSeconds;
        }

        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException nfe) {
            return defaultSeconds;
        }
    }

    private boolean isStatusHealthy(int status, int expected) {
        if (expected > 0) {
            return status == expected;
        }

        return status >= 200 && status < 300;
    }

    private void applyHeaders(
            NodeExecutionContext ctx,
            HttpRequest.Builder request) {
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
                ctx.log().warn(
                        "Skipped restricted request header \""
                                + entry.getKey()
                                + "\".");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(
            NodeExecutionContext ctx,
            Object raw) {
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
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }

    /** Host + path only in logs — never the query string. */
    private String safeTarget(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();

        return uri.getScheme() + "://" + host + path;
    }

    private String sanitize(Exception failure) {
        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message;
    }
}