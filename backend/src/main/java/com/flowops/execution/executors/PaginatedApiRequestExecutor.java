package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Paginated API Request: walks a cursor-based paginated endpoint. Each page is fetched,
 * the {@code itemsJsonPath} (a dotted path) is collected into a running list, and the
 * next page's cursor is read at {@code nextCursorPath}. The cursor is sent back as the
 * {@code cursorParam} query parameter; iteration stops when a page has no next cursor or
 * {@code maxPages} pages have been fetched.
 */
@Component
public class PaginatedApiRequestExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public PaginatedApiRequestExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "paginated_api_request";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail("Paginated API Request has no URL configured.");
        }

        URI baseUri;
        try {
            baseUri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail("Paginated API Request URL is not a valid or allowed URI.");
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "Paginated API Request URL rejected: " + blocked.getMessage()
            );
        }

        if (baseUri.getScheme() == null
                || !(baseUri.getScheme().equals("http")
                || baseUri.getScheme().equals("https"))) {
            return NodeResult.fail("Paginated API Request URL must be http or https.");
        }

        String itemsJsonPath = orDefault(
                ctx.configString("itemsJsonPath"),
                "items"
        );

        String nextCursorPath = ctx.configString("nextCursorPath");

        if (nextCursorPath == null || nextCursorPath.isBlank()) {
            return NodeResult.fail(
                    "Paginated API Request has no nextCursorPath configured."
            );
        }

        String cursorParam = orDefault(
                ctx.configString("cursorParam"),
                "cursor"
        );

        int maxPages = Math.max(
                1,
                parseInt(ctx.configString("maxPages"), 10)
        );

        String method = orDefault(
                ctx.configString("method"),
                "GET"
        ).toUpperCase(Locale.ROOT);

        ArrayNode allItems = ctx.mapper().createArrayNode();

        int pagesFetched = 0;
        String nextCursor = null;

        do {
            if (nextCursor != null) {
                baseUri = withQueryParam(
                        baseUri,
                        cursorParam,
                        nextCursor
                );
            }

            HttpResponse<String> response = send(
                    ctx,
                    baseUri,
                    method
            );

            pagesFetched++;

            JsonNode body = parseBody(
                    ctx,
                    response
            );

            JsonNode items = resolvePath(
                    body,
                    itemsJsonPath
            );

            if (items.isArray()) {
                items.forEach(allItems::add);
            }

            JsonNode nextNode = resolvePath(
                    body,
                    nextCursorPath
            );

            nextCursor = nextNode.isNull()
                    || nextNode.isMissingNode()
                    ? null
                    : (nextNode.isValueNode()
                    ? nextNode.asText()
                    : nextNode.toString());

            if (nextCursor != null
                    && nextCursor.isEmpty()
                    && !nextNode.isMissingNode()) {
                nextCursor = null;
            }

            if (pagesFetched >= maxPages) {
                break;
            }

        } while (nextCursor != null);

        ObjectNode output = ctx.mapper().createObjectNode();

        output.put(
                "pagesFetched",
                pagesFetched
        );

        output.put(
                "maxPages",
                maxPages
        );

        output.put(
                "totalItems",
                allItems.size()
        );

        output.put(
                "lastCursor",
                nextCursor
        );

        output.set(
                "items",
                allItems
        );

        ctx.log().info(
                "Paginated "
                        + safeTarget(baseUri)
                        + " fetched "
                        + pagesFetched
                        + " page(s), "
                        + allItems.size()
                        + " item(s) total."
        );

        return NodeResult.success(output);
    }

    private HttpResponse<String> send(
            NodeExecutionContext ctx,
            URI uri,
            String method
    ) throws Exception {

        String body = ctx.configString("body");

        HttpRequest.BodyPublisher publisher =
                (body == null || body.isEmpty())
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        );

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(ctx.properties().httpRequestTimeout())
                .method(method, publisher);

        applyHeaders(
                ctx,
                request
        );

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

        ctx.log().info(
                "Paginated "
                        + safeTarget(uri)
                        + " → "
                        + response.statusCode()
                        + " ("
                        + millis
                        + "ms)"
        );

        return response;
    }

    /** Appends (or sets) a query parameter on a URI without touching the fragment. */
    private URI withQueryParam(
            URI base,
            String param,
            String value
    ) {

        String rawQuery = base.getRawQuery();

        String encodedParam =
                java.net.URLEncoder.encode(
                        param,
                        StandardCharsets.UTF_8
                );

        String encodedValue =
                java.net.URLEncoder.encode(
                        value,
                        StandardCharsets.UTF_8
                );

        String newQuery =
                (rawQuery == null || rawQuery.isEmpty())
                        ? encodedParam + "=" + encodedValue
                        : rawQuery
                                + "&"
                                + encodedParam
                                + "="
                                + encodedValue;

        return URI.create(
                base.getScheme()
                        + "://"
                        + base.getRawAuthority()
                        + base.getRawPath()
                        + "?"
                        + newQuery
        );
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
                // Fall through to text node.
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

    /**
     * Walks a dotted path (e.g. {@code data.items}) against a JSON node;
     * missing if any segment is absent.
     */
    private JsonNode resolvePath(
            JsonNode root,
            String path
    ) {

        if (root == null
                || root.isMissingNode()
                || path == null
                || path.isBlank()) {

            return MissingNode.getInstance();
        }

        JsonNode current = root;

        for (String rawSegment : path.trim().split("\\.")) {

            String segment = rawSegment.trim();

            if (segment.isEmpty()
                    || current == null
                    || current.isMissingNode()
                    || current.isNull()) {

                return MissingNode.getInstance();
            }

            if (current.isArray()) {

                try {
                    current =
                            current.path(
                                    Integer.parseInt(segment)
                            );

                } catch (NumberFormatException notAnIndex) {
                    return MissingNode.getInstance();
                }

            } else {

                current =
                        current.path(segment);
            }
        }

        return current == null
                ? MissingNode.getInstance()
                : current;
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
                // Return empty map below.
            }
        }

        return Map.of();
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