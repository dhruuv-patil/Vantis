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
 * Wait For HTTP: polls a URL until it returns {@code desiredStatus} (default 200) or
 * {@code timeoutSeconds} elapse. Each poll uses the same request config. The result
 * reports the last status, whether it matched, and how long the wait ran.
 */
@Component
public class WaitForHttpExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public WaitForHttpExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "wait_for_http";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail("Wait For HTTP has no URL configured.");
        }

        URI uri;

        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail(
                    "Wait For HTTP URL is not a valid or allowed URI."
            );
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "Wait For HTTP URL rejected: " + blocked.getMessage()
            );
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                || uri.getScheme().equals("https"))) {
            return NodeResult.fail(
                    "Wait For HTTP URL must be http or https."
            );
        }

        String method =
                orDefault(
                        ctx.configString("method"),
                        "GET"
                ).toUpperCase(Locale.ROOT);

        int desiredStatus =
                parseStatus(
                        ctx.configString("desiredStatus"),
                        200
                );

        int timeoutSeconds =
                Math.max(
                        1,
                        parseInt(
                                ctx.configString("timeoutSeconds"),
                                30
                        )
                );

        int pollIntervalSeconds =
                Math.max(
                        1,
                        parseInt(
                                ctx.configString("pollIntervalSeconds"),
                                5
                        )
                );

        long deadlineNanos =
                System.nanoTime()
                        + Duration.ofSeconds(timeoutSeconds).toNanos();

        int polls = 0;

        HttpResponse<String> lastResponse = null;
        String lastError = null;

        while (System.nanoTime() < deadlineNanos) {

            polls++;

            String body =
                    ctx.configString("body");

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

            try {

                lastResponse =
                        client.send(
                                request.build(),
                                HttpResponse.BodyHandlers.ofString(
                                        StandardCharsets.UTF_8
                                )
                        );

                long millis =
                        (System.nanoTime() - startedAt) / 1_000_000;

                ctx.log().info(
                        "Wait For HTTP "
                                + safeTarget(uri)
                                + " poll #"
                                + polls
                                + " → "
                                + lastResponse.statusCode()
                                + " ("
                                + millis
                                + "ms)"
                );

                if (lastResponse.statusCode() == desiredStatus) {

                    return NodeResult.success(
                            buildOutput(
                                    ctx,
                                    lastResponse,
                                    polls,
                                    true,
                                    null
                            )
                    );
                }

            } catch (Exception e) {

                lastError = sanitize(e);

                ctx.log().warn(
                        "Wait For HTTP "
                                + safeTarget(uri)
                                + " poll #"
                                + polls
                                + " failed: "
                                + lastError
                );

                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
            }

            long remainingNanos =
                    deadlineNanos - System.nanoTime();

            if (remainingNanos <= 0) {
                break;
            }

            Thread.sleep(
                    Duration.ofMillis(
                            Math.min(
                                    Duration.ofSeconds(
                                            pollIntervalSeconds
                                    ).toMillis(),
                                    remainingNanos / 1_000_000
                            )
                    )
            );
        }

        ctx.log().warn(
                "Wait For HTTP "
                        + safeTarget(uri)
                        + " timed out after "
                        + timeoutSeconds
                        + "s (last status: "
                        + (lastResponse == null
                        ? "none"
                        : lastResponse.statusCode())
                        + ")."
        );

        return NodeResult.success(
                buildOutput(
                        ctx,
                        lastResponse,
                        polls,
                        false,
                        lastError
                )
        );
    }

    private ObjectNode buildOutput(
            NodeExecutionContext ctx,
            HttpResponse<String> response,
            int polls,
            boolean matched,
            String error
    ) {

        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put(
                "matched",
                matched
        );

        output.put(
                "polls",
                polls
        );

        if (response == null) {

            output.put(
                    "status",
                    0
            );

            output.put(
                    "ok",
                    false
            );

            if (error != null) {
                output.put(
                        "error",
                        error
                );
            }

            return output;
        }

        output.put(
                "status",
                response.statusCode()
        );

        output.put(
                "ok",
                response.statusCode() >= 200
                        && response.statusCode() < 300
        );

        output.put(
                "contentType",
                response.headers()
                        .firstValue("content-type")
                        .orElse("")
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

        if (response.body() != null) {
            output.set(
                    "body",
                    parseBody(
                            ctx,
                            response
                    )
            );
        }

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

    private int parseInt(
            String value,
            int fallback
    ) {

        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(
                    value.trim()
            );
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    private int parseStatus(
            String value,
            int fallback
    ) {

        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Math.max(
                    1,
                    Integer.parseInt(
                            value.trim()
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

    private String sanitize(Exception failure) {

        String message =
                failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass()
                    .getSimpleName();
        }

        return message;
    }
}