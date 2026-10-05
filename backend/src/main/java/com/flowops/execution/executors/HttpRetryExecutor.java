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
 * HTTP Retry: calls a URL up to {@code maxAttempts} times with an exponential backoff
 * ({@code backoffSeconds} doubles after each attempt). A non-2xx status or a transport
 * failure triggers another attempt. When calls are healthy on some later attempt the
 * response is returned; if every attempt fails the last response is returned along with
 * the attempt count so downstream logic can see what happened.
 */
@Component
public class HttpRetryExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public HttpRetryExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "http_retry";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail(
                    "HTTP Retry has no URL configured."
            );
        }

        URI uri;

        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException
                 | com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "HTTP Retry target rejected: "
                            + blocked.getMessage()
            );
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                || uri.getScheme().equals("https"))) {
            return NodeResult.fail(
                    "HTTP Retry URL must be http or https."
            );
        }

        String method = orDefault(
                ctx.configString("method"),
                "GET"
        ).toUpperCase(Locale.ROOT);

        int maxAttempts = parseInt(
                ctx.configString("maxAttempts"),
                3
        );

        double backoffSeconds = parseDouble(
                ctx.configString("backoffSeconds"),
                1.0
        );

        HttpResponse<String> lastResponse = null;
        String lastError = null;

        for (int attempt = 1;
             attempt <= maxAttempts;
             attempt++) {

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

            try {
                lastResponse =
                        client.send(
                                request.build(),
                                HttpResponse.BodyHandlers.ofString(
                                        StandardCharsets.UTF_8
                                )
                        );

                long millis =
                        (System.nanoTime() - startedAt)
                                / 1_000_000;

                ctx.log().info(
                        "HTTP Retry "
                                + safeTarget(uri)
                                + " attempt "
                                + attempt
                                + "/"
                                + maxAttempts
                                + " → "
                                + lastResponse.statusCode()
                                + " ("
                                + millis
                                + "ms)"
                );

                if (lastResponse.statusCode() >= 200
                        && lastResponse.statusCode() < 300) {

                    return NodeResult.success(
                            buildOutput(
                                    ctx,
                                    lastResponse,
                                    attempt,
                                    maxAttempts,
                                    false,
                                    null
                            )
                    );
                }

            } catch (Exception e) {
                lastError = sanitize(e);

                ctx.log().warn(
                        "HTTP Retry "
                                + safeTarget(uri)
                                + " attempt "
                                + attempt
                                + "/"
                                + maxAttempts
                                + " failed: "
                                + lastError
                );
            }

            if (attempt < maxAttempts) {
                long backoffMillis =
                        (long) (
                                backoffSeconds
                                        * Math.pow(2, attempt - 1)
                                        * 1000L
                        );

                Thread.sleep(
                        Duration.ofMillis(backoffMillis)
                );
            }
        }

        ctx.log().warn(
                "HTTP Retry "
                        + safeTarget(uri)
                        + " exhausted "
                        + maxAttempts
                        + " attempt(s);"
                        + (
                        lastError != null
                                ? " last error: " + lastError
                                : ""
                )
        );

        return NodeResult.success(
                buildOutput(
                        ctx,
                        lastResponse,
                        maxAttempts,
                        maxAttempts,
                        true,
                        lastError
                )
        );
    }

    private ObjectNode buildOutput(
            NodeExecutionContext ctx,
            HttpResponse<String> response,
            int attemptsUsed,
            int maxAttempts,
            boolean exhausted,
            String error
    ) {
        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put(
                "attempts",
                attemptsUsed
        );

        output.put(
                "maxAttempts",
                maxAttempts
        );

        output.put(
                "exhausted",
                exhausted
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

        for (Map.Entry<String, Object> entry :
                headers.entrySet()) {

            if (entry.getKey() == null
                    || entry.getValue() == null) {
                continue;
            }

            String value =
                    ctx.interpolate(
                            String.valueOf(entry.getValue())
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
                    return ctx.mapper().convertValue(
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

    private int parseInt(
            String value,
            int fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Math.max(
                    1,
                    Integer.parseInt(value.trim())
            );
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    private double parseDouble(
            String value,
            double fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Math.max(
                    0.1,
                    Double.parseDouble(value.trim())
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
            return failure.getClass().getSimpleName();
        }

        return message;
    }
}