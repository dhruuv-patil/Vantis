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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Rate Limited Request: issues a single request, but paces it so that this node never
 * sends more than {@code requestsPerSecond} requests. Because the executor is a singleton
 * bean the pacing is shared across all workflow runs, which keeps a busy endpoint honest
 * under concurrent executions. Extra latency added by the sleep is reported on the output.
 */
@Component
public class RateLimitedRequestExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    /** Nano-timestamp of the previous request sent through this node; guarded by sink(). */
    private final AtomicLong lastSendNanos = new AtomicLong(0);

    private final HttpClient client;

    public RateLimitedRequestExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "rate_limited_request";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail("Rate Limited Request has no URL configured.");
        }

        URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail(
                    "Rate Limited Request URL is not a valid or allowed URI."
            );
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "Rate Limited Request URL rejected: " + blocked.getMessage()
            );
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                || uri.getScheme().equals("https"))) {
            return NodeResult.fail(
                    "Rate Limited Request URL must be http or https."
            );
        }

        double requestsPerSecond =
                parseRate(
                        ctx.configString("requestsPerSecond"),
                        1.0
                );

        long pacingNanos =
                (long) (1_000_000_000.0 / requestsPerSecond);

        long throttledNanos = sink(pacingNanos);

        String method =
                orDefault(
                        ctx.configString("method"),
                        "GET"
                ).toUpperCase(Locale.ROOT);

        String body = ctx.configString("body");

        HttpRequest.BodyPublisher publisher =
                (body == null || body.isEmpty())
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        );

        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(
                                ctx.properties().httpRequestTimeout()
                        )
                        .method(
                                method,
                                publisher
                        );

        applyHeaders(ctx, request);

        long startedAt = System.nanoTime();

        HttpResponse<String> response =
                client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        long millis =
                (System.nanoTime() - startedAt) / 1_000_000;

        ObjectNode output =
                buildOutput(
                        ctx,
                        response
                );

        output.put(
                "throttledMs",
                throttledNanos / 1_000_000
        );

        output.put(
                "requestsPerSecond",
                requestsPerSecond
        );

        ctx.log().info(
                "Rate-limited "
                        + method
                        + " "
                        + safeTarget(uri)
                        + " → "
                        + response.statusCode()
                        + " ("
                        + millis
                        + "ms, throttled "
                        + (throttledNanos / 1_000_000)
                        + "ms)"
        );

        return NodeResult.success(output);
    }

    /**
     * Sleeps until at least {@code pacingNanos} have passed since the previous send,
     * then records the send; returns how long we actually waited.
     */
    private long sink(long pacingNanos) throws InterruptedException {
        long now = System.nanoTime();

        long committed =
                lastSendNanos.getAndUpdate(
                        previous -> Math.max(previous, now)
                );

        long waitNanos =
                (committed + pacingNanos) - now;

        if (waitNanos > 0) {
            Thread.sleep(
                    Duration.ofNanos(waitNanos)
            );
            return waitNanos;
        }

        // Reserve the slot even when there's nothing to wait for.
        lastSendNanos.getAndUpdate(
                previous -> Math.max(
                        previous,
                        System.nanoTime()
                )
        );

        return 0;
    }

    private ObjectNode buildOutput(
            NodeExecutionContext ctx,
            HttpResponse<String> response
    ) {

        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put(
                "status",
                response.statusCode()
        );

        output.put(
                "ok",
                response.statusCode() >= 200
                        && response.statusCode() < 300
        );

        ObjectNode headers =
                ctx.mapper().createObjectNode();

        response.headers().map().forEach(
                (name, values) -> {

                    String value =
                            MASKED_RESPONSE_HEADERS.contains(
                                    name.toLowerCase(Locale.ROOT)
                            )
                                    ? "***"
                                    : String.join(
                                            ", ",
                                            values
                                    );

                    headers.put(
                            name,
                            value
                    );
                }
        );

        output.set(
                "headers",
                headers
        );

        output.set(
                "body",
                parseBody(
                        ctx,
                        response
                )
        );

        return output;
    }

    private void applyHeaders(
            NodeExecutionContext ctx,
            HttpRequest.Builder request
    ) {

        Object rawHeaders =
                ctx.config().get("headers");

        Map<String, Object> headers =
                asMap(
                        ctx,
                        rawHeaders
                );

        for (Map.Entry<String, Object> entry
                : headers.entrySet()) {

            if (entry.getKey() == null
                    || entry.getValue() == null) {
                continue;
            }

            String value =
                    ctx.interpolate(
                            String.valueOf(
                                    entry.getValue()
                            )
                    );

            try {

                request.header(
                        entry.getKey(),
                        value
                );

            } catch (IllegalArgumentException restricted) {

                ctx.log().warn(
                        "Skipped restricted request header \""
                                + entry.getKey()
                                + "\"."
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(
            NodeExecutionContext ctx,
            Object raw
    ) {

        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        if (raw instanceof String text
                && !text.isBlank()) {

            try {

                JsonNode parsed =
                        ctx.mapper().readTree(text);

                if (parsed.isObject()) {

                    return ctx.mapper()
                            .convertValue(
                                    parsed,
                                    Map.class
                            );
                }

            } catch (Exception ignored) {
            }
        }

        return Map.of();
    }

    private JsonNode parseBody(
            NodeExecutionContext ctx,
            HttpResponse<String> response
    ) {

        String raw =
                response.body() == null
                        ? ""
                        : response.body();

        int cap =
                ctx.properties().httpMaxBodyBytes();

        boolean truncated =
                raw.length() > cap;

        String captured =
                truncated
                        ? raw.substring(0, cap)
                        : raw;

        String contentType =
                response.headers()
                        .firstValue("content-type")
                        .orElse("");

        if (!truncated
                && contentType
                        .toLowerCase(Locale.ROOT)
                        .contains("json")
                && !captured.isBlank()) {

            try {
                return ctx.mapper().readTree(captured);
            } catch (Exception notJson) {
            }
        }

        if (truncated) {
            ctx.log().warn(
                    "Response body exceeded "
                            + cap
                            + " bytes and was truncated."
            );

            return ctx.mapper()
                    .getNodeFactory()
                    .textNode(captured);
        }

        return ctx.mapper()
                .getNodeFactory()
                .textNode(captured);
    }

    private String orDefault(
            String value,
            String fallback
    ) {

        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }

    private double parseRate(
            String value,
            double fallback
    ) {

        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {

            double rate =
                    Double.parseDouble(
                            value.trim()
                    );

            return Math.max(
                    0.001,
                    Math.min(
                            rate,
                            1000.0
                    )
            );

        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    /** Host + path only in logs — never the query string. */
    private String safeTarget(URI uri) {

        String host =
                uri.getHost() == null
                        ? ""
                        : uri.getHost();

        String path =
                uri.getRawPath() == null
                        ? ""
                        : uri.getRawPath();

        return uri.getScheme()
                + "://"
                + host
                + path;
    }
}