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
import org.springframework.stereotype.Component;

/**
 * GraphQL Request: posts a GraphQL operation to an endpoint and captures the response.
 * URL, query, variables, and headers all support {{variable}} interpolation.
 */
@Component
public class GraphQLRequestExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public GraphQLRequestExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "graphql_request";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");
        if (url == null || url.isBlank()) {
            return NodeResult.fail("GraphQL Request has no URL configured.");
        }

        URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail("GraphQL Request URL is not a valid URI.");
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return NodeResult.fail("GraphQL Request URL must be http or https.");
        }

        String query = ctx.configString("query");
        if (query == null || query.isBlank()) {
            return NodeResult.fail("GraphQL Request has no query configured.");
        }

        Object rawVariables = ctx.config().get("variables");
        Map<String, Object> variables = asMap(ctx, rawVariables);

        ObjectNode requestBody = ctx.mapper().createObjectNode();
        requestBody.put("query", query);

        if (variables != null && !variables.isEmpty()) {
            requestBody.set(
                    "variables",
                    ctx.mapper().valueToTree(variables)
            );
        }

        String body = requestBody.toString();

        HttpRequest.BodyPublisher publisher =
                HttpRequest.BodyPublishers.ofString(
                        body,
                        StandardCharsets.UTF_8
                );

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(ctx.properties().httpRequestTimeout())
                .header("Content-Type", "application/json")
                .POST(publisher);

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

        ObjectNode output = buildOutput(
                ctx,
                response
        );

        ctx.log().info(
                "GraphQL "
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
        Object rawHeaders = ctx.config().get("headers");

        Map<String, Object> headers =
                asMap(ctx, rawHeaders);

        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
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

        if (raw instanceof String text && !text.isBlank()) {
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
                                    : String.join(", ", values);

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
                parseBody(ctx, response)
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
                // Fall through and return the raw body.
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