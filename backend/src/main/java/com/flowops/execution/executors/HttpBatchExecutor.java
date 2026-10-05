package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * HTTP Batch: runs one request per item in the {@code items} array, in parallel up to a
 * {@code concurrency} limit, and collects the results in input order. Each item may
 * carry its own {@code url}, {@code method}, {@code body}, and {@code headers} — any of
 * which fall back to the top-level config when absent.
 */
@Component
public class HttpBatchExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public HttpBatchExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "http_batch";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        JsonNode items = resolveItems(ctx);

        if (items == null || !items.isArray() || items.isEmpty()) {
            return NodeResult.fail(
                    "HTTP Batch has no items array configured."
            );
        }

        int concurrency = parseInt(
                ctx.configString("concurrency"),
                5
        );

        Semaphore semaphore = new Semaphore(concurrency);

        ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<CompletableFuture<ObjectNode>> futures =
                    new ArrayList<>();

            for (JsonNode item : items) {
                futures.add(
                        CompletableFuture.supplyAsync(
                                () -> fetch(ctx, item, semaphore),
                                executor
                        )
                );
            }

            ArrayNode results =
                    ctx.mapper().createArrayNode();

            int failures = 0;

            for (CompletableFuture<ObjectNode> future : futures) {
                ObjectNode result = future.join();

                results.add(result);

                if (!result.path("ok").asBoolean(false)) {
                    failures++;
                }
            }

            ObjectNode output =
                    ctx.mapper().createObjectNode();

            output.put(
                    "totalItems",
                    items.size()
            );

            output.put(
                    "concurrency",
                    concurrency
            );

            output.put(
                    "failures",
                    failures
            );

            output.set(
                    "results",
                    results
            );

            ctx.log().info(
                    "HTTP Batch ran "
                            + items.size()
                            + " request(s) at concurrency "
                            + concurrency
                            + " → "
                            + (items.size() - failures)
                            + " ok, "
                            + failures
                            + " failed."
            );

            return NodeResult.success(output);

        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode resolveItems(NodeExecutionContext ctx) {
        Object raw = ctx.config().get("items");

        if (raw instanceof JsonNode node) {
            return node.isArray()
                    ? node
                    : ctx.mapper().valueToTree(raw);
        }

        if (raw instanceof String text && !text.isBlank()) {
            try {
                JsonNode parsed =
                        ctx.mapper().readTree(text);

                if (parsed.isArray()) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }

        if (raw != null) {
            return ctx.mapper().valueToTree(raw);
        }

        return null;
    }

    private ObjectNode fetch(
            NodeExecutionContext ctx,
            JsonNode item,
            Semaphore semaphore
    ) {
        try {
            semaphore.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            return failure(
                    ctx,
                    "batch interrupted",
                    0
            );
        }

        try {
            return doFetch(ctx, item);
        } catch (Exception e) {
            return failure(
                    ctx,
                    sanitize(e),
                    0
            );
        } finally {
            semaphore.release();
        }
    }

    private ObjectNode doFetch(
            NodeExecutionContext ctx,
            JsonNode item
    ) throws Exception {

        String url = item.path("url").asText();

        if (url == null || url.isBlank()) {
            return failure(
                    ctx,
                    "item has no url",
                    0
            );
        }

        url = ctx.interpolate(url);

        URI uri;

        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return failure(
                    ctx,
                    "item URL is not a valid URI",
                    0
            );
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                || uri.getScheme().equals("https"))) {

            return failure(
                    ctx,
                    "item URL must be http or https",
                    0
            );
        }

        String method =
                item.path("method").asText();

        if (method == null || method.isBlank()) {
            method = orDefault(
                    ctx.configString("method"),
                    "GET"
            );
        }

        method = method.toUpperCase(Locale.ROOT);

        String body =
                item.path("body").asText();

        if (body.isEmpty()) {
            body = ctx.configString("body") == null
                    ? ""
                    : ctx.configString("body");
        }

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

        applyHeaders(
                ctx,
                request,
                item
        );

        long startedAt =
                System.nanoTime();

        HttpResponse<String> response =
                client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        long millis =
                (System.nanoTime() - startedAt)
                        / 1_000_000;

        ObjectNode output =
                buildOutput(
                        ctx,
                        response,
                        uri
                );

        output.put(
                "latencyMs",
                millis
        );

        return output;
    }

    private ObjectNode buildOutput(
            NodeExecutionContext ctx,
            HttpResponse<String> response,
            URI uri
    ) {
        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put(
                "url",
                safeTarget(uri)
        );

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

    private ObjectNode failure(
            NodeExecutionContext ctx,
            String message,
            int status
    ) {
        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put(
                "status",
                status
        );

        output.put(
                "ok",
                false
        );

        output.put(
                "error",
                message
        );

        return output;
    }

    /**
     * Merges headers from the batch-level config with item-level overrides. Item headers
     * win on conflict.
     */
    private void applyHeaders(
            NodeExecutionContext ctx,
            HttpRequest.Builder request,
            JsonNode item
    ) {
        Map<String, Object> base =
                asMap(
                        ctx,
                        ctx.config().get("headers")
                );

        Map<String, Object> itemHeaders =
                asMap(
                        ctx,
                        item.get("headers")
                );

        for (Map.Entry<String, Object> entry :
                base.entrySet()) {

            addHeader(
                    ctx,
                    request,
                    entry.getKey(),
                    String.valueOf(entry.getValue())
            );
        }

        for (Map.Entry<String, Object> entry :
                itemHeaders.entrySet()) {

            addHeader(
                    ctx,
                    request,
                    entry.getKey(),
                    String.valueOf(entry.getValue())
            );
        }
    }

    private void addHeader(
            NodeExecutionContext ctx,
            HttpRequest.Builder request,
            String key,
            String value
    ) {
        if (key == null || value == null) {
            return;
        }

        String interpolated =
                ctx.interpolate(value);

        try {
            request.header(
                    key,
                    interpolated
            );
        } catch (IllegalArgumentException restricted) {
            ctx.log().warn(
                    "Skipped restricted request header \""
                            + key
                            + "\"."
            );
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