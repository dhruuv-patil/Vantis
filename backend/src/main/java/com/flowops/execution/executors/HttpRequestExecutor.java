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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * HTTP Request: calls an external endpoint and captures the response as this node's
 * output ({@code status}, {@code headers}, {@code body}). URL, headers, and body all
 * support {@code {{variable}}} interpolation.
 *
 * <p>Safety: connect and request timeouts and a response-body cap come from
 * {@code flowops.execution.*}. Sensitive response headers ({@code set-cookie},
 * {@code authorization}, …) are masked in the stored output, and neither request
 * headers nor bodies are ever written to the run log (contract §9) — only the
 * method, URL, status, and timing.
 */
@Component
public class HttpRequestExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public HttpRequestExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "http_request";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String method = orDefault(
                ctx.configString("method"),
                "GET"
        ).toUpperCase(Locale.ROOT);

        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail(
                    "HTTP Request has no URL configured."
            );
        }

        URI uri;

        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException
                 | com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "HTTP Request target rejected: "
                            + blocked.getMessage()
            );
        }

        // Circuit breaker check
        String host = uri.getHost();
        if (ctx.circuitBreakerService() != null && ctx.organizationId() != null && host != null) {
            if (!ctx.circuitBreakerService().allowRequest(ctx.organizationId(), host)) {
                return NodeResult.fail(
                        "Circuit breaker is OPEN for target host: " + host
                                + ". Requests temporarily paused to prevent cascading failures."
                );
            }
        }

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

        // Inject integration auth if present
        String authHeader = ctx.secret("authHeaderName");
        String token = ctx.secret("apiToken");

        if (authHeader != null && token != null) {
            String headerName =
                    ctx.interpolate(authHeader).trim();

            String headerValue =
                    ctx.interpolate(token).trim();

            if (headerName.equalsIgnoreCase("Authorization")
                    && !headerValue
                            .toLowerCase(Locale.ROOT)
                            .startsWith("bearer ")
                    && !headerValue
                            .toLowerCase(Locale.ROOT)
                            .startsWith("basic ")) {

                headerValue = "Bearer " + headerValue;
            }

            request.header(
                    headerName,
                    headerValue
            );
        }

        applyHeaders(ctx, request);

        // Idempotency check for external side-effecting operations
        boolean isSideEffect = Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
        String userKey = ctx.configString("idempotencyKey");
        String idempotencyKey = (userKey != null && !userKey.isBlank())
                ? userKey
                : (ctx.executionId() != null ? "http:" + ctx.executionId() + ":" + ctx.node().id() : null);

        UUID idempRecordId = null;
        if (isSideEffect && idempotencyKey != null && ctx.idempotencyService() != null && ctx.organizationId() != null) {
            var cached = ctx.idempotencyService().findCompleted(ctx.organizationId(), idempotencyKey);
            if (cached.isPresent() && cached.get().getResponsePayload() != null) {
                ctx.log().info("Idempotent replay: using cached response for key=" + idempotencyKey);
                return NodeResult.success(cached.get().getResponsePayload());
            }

            var rec = ctx.idempotencyService().recordStart(
                    ctx.organizationId(),
                    ctx.executionId(),
                    ctx.node().id(),
                    idempotencyKey,
                    ctx.attempt());
            idempRecordId = rec.getId();

            try {
                request.header("Idempotency-Key", idempotencyKey);
            } catch (Exception ignored) {
                // Header might already be present from custom headers
            }
        }

        long startedAt = System.nanoTime();

        HttpResponse<String> response;
        try {
            response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );

            if (ctx.circuitBreakerService() != null && ctx.organizationId() != null && host != null) {
                if (response.statusCode() >= 500) {
                    ctx.circuitBreakerService().recordFailure(ctx.organizationId(), host);
                } else {
                    ctx.circuitBreakerService().recordSuccess(ctx.organizationId(), host);
                }
            }
        } catch (Exception sendFailure) {
            if (ctx.circuitBreakerService() != null && ctx.organizationId() != null && host != null) {
                ctx.circuitBreakerService().recordFailure(ctx.organizationId(), host);
            }
            if (idempRecordId != null && ctx.idempotencyService() != null) {
                ObjectNode errNode = ctx.mapper().createObjectNode();
                errNode.put("error", sendFailure.getMessage());
                ctx.idempotencyService().recordFailure(idempRecordId, errNode);
            }
            throw sendFailure;
        }

        long millis =
                (System.nanoTime() - startedAt)
                        / 1_000_000;

        ObjectNode output =
                buildOutput(
                        ctx,
                        response
                );

        if (idempRecordId != null && ctx.idempotencyService() != null) {
            if (response.statusCode() < 400) {
                ctx.idempotencyService().recordSuccess(idempRecordId, output);
            } else {
                ctx.idempotencyService().recordFailure(idempRecordId, output);
            }
        }

        ctx.log().info(
                method
                        + " "
                        + safeTarget(uri)
                        + " → "
                        + response.statusCode()
                        + " ("
                        + millis
                        + "ms)"
        );

        return NodeResult.success(output);
    }

    private void applyHeaders(
            NodeExecutionContext ctx,
            HttpRequest.Builder request
    ) {
        Object rawHeaders =
                ctx.config().get("headers");

        Map<String, Object> headers =
                asHeaderMap(
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
                // The JDK client forbids some headers (Host, Content-Length, …);
                // skip without echoing the value, which could be a secret.
                ctx.log().warn(
                        "Skipped restricted request header \""
                                + entry.getKey()
                                + "\"."
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asHeaderMap(
            NodeExecutionContext ctx,
            Object rawHeaders
    ) {
        if (rawHeaders instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        if (rawHeaders instanceof String text
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
                // fall through to empty
            }
        }

        return Map.of();
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
                // fall through to text
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
                    .textNode(
                            captured + "…[truncated]"
                    );
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

    /** Host + path only in logs — never the query string, which may carry tokens. */
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