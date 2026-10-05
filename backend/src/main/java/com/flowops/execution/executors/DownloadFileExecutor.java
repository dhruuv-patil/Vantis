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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Download File: performs a GET and captures the raw bytes of the response. When
 * {@code targetPath} is set the payload is written to a confined temporary
 * download directory and the path is returned; otherwise (or when
 * {@code returnBase64} is enabled) the bytes are returned inline as base64 so a
 * downstream node can embed or forward them.
 */
@Component
public class DownloadFileExecutor implements NodeExecutor {

    private static final Set<String> MASKED_RESPONSE_HEADERS = Set.of(
            "set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

    private final HttpClient client;

    public DownloadFileExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "download_file";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");
        if (url == null || url.isBlank()) {
            return NodeResult.fail("Download File has no URL configured.");
        }

        URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail("Download File URL is not a valid URI.");
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return NodeResult.fail("Download File URL must be http or https.");
        }

        String targetPath = ctx.configString("targetPath");
        boolean returnBase64 = parseBool(
                ctx.configString("returnBase64"),
                false
        );

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(ctx.properties().httpRequestTimeout())
                .header("Accept-Encoding", "identity")
                .GET();

        applyHeaders(ctx, request);

        long startedAt = System.nanoTime();

        HttpResponse<byte[]> response =
                client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                );

        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        int status = response.statusCode();

        byte[] bytes = response.body() == null
                ? new byte[0]
                : response.body();

        String contentType = response.headers()
                .firstValue("content-type")
                .orElse("application/octet-stream");

        ObjectNode output = ctx.mapper().createObjectNode();

        output.put("status", status);
        output.put("ok", status >= 200 && status < 300);
        output.put("contentType", contentType);
        output.put("bytes", bytes.length);

        String savedPath = saveIfRequested(
                ctx,
                targetPath,
                bytes
        );

        if (savedPath != null) {
            output.put("path", savedPath);
        }

        if (returnBase64) {
            output.put(
                    "base64",
                    Base64.getEncoder().encodeToString(bytes)
            );
        }

        ctx.log().info(
                "Download "
                        + safeTarget(uri)
                        + " → "
                        + status
                        + " ("
                        + bytes.length
                        + " bytes, "
                        + millis
                        + "ms)"
        );

        return NodeResult.success(output);
    }

    /**
     * Writes the payload to a confined temporary download directory.
     */
    private String saveIfRequested(
            NodeExecutionContext ctx,
            String targetPath,
            byte[] bytes
    ) {
        if (targetPath == null || targetPath.isBlank()) {
            return null;
        }

        try {
            Path base = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "flowops-downloads"
            ).toAbsolutePath().normalize();

            Path absolute = base
                    .resolve(targetPath.trim())
                    .normalize();

            if (!absolute.startsWith(base) || absolute.equals(base)) {
                ctx.log().warn(
                        "Download targetPath is outside the allowed download directory and was ignored."
                );
                return null;
            }

            Files.createDirectories(absolute.getParent());

            Files.write(
                    absolute,
                    bytes
            );

            ctx.log().info(
                    "Download saved "
                            + bytes.length
                            + " bytes to "
                            + absolute
                            + "."
            );

            return absolute.toString();

        } catch (Exception e) {
            ctx.log().warn(
                    "Download could not write targetPath \""
                            + targetPath
                            + "\": "
                            + sanitize(e)
            );

            return null;
        }
    }

    private void applyHeaders(
            NodeExecutionContext ctx,
            HttpRequest.Builder request
    ) {
        Object rawHeaders = ctx.config().get("headers");

        Map<String, Object> headers = asMap(
                ctx,
                rawHeaders
        );

        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String value = ctx.interpolate(
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
                JsonNode parsed = ctx.mapper().readTree(text);

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

    private boolean parseBool(
            String value,
            boolean fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Boolean.parseBoolean(value.trim())
                || "1".equals(value.trim());
    }

    /**
     * Host + path only in logs — never the query string.
     */
    private String safeTarget(URI uri) {
        String host = uri.getHost() == null
                ? ""
                : uri.getHost();

        String path = uri.getRawPath() == null
                ? ""
                : uri.getRawPath();

        return uri.getScheme()
                + "://"
                + host
                + path;
    }

    private String sanitize(Exception failure) {
        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message;
    }
}