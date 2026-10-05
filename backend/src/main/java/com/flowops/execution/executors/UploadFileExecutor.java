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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Upload File: sends {@code filePath} or {@code inlineContent} as a multipart/form-data
 * upload (POST or PUT). The file payload rides a single {@code file} part; extra form
 * fields from config {@code fields} are appended as additional parts. Body, filename,
 * and content type all support {@code {{variable}}} interpolation.
 */
@Component
public class UploadFileExecutor implements NodeExecutor {

    private static final String CRLF = "\r\n";

    private final HttpClient client;

    public UploadFileExecutor(ExecutionProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "upload_file";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) throws Exception {
        String url = ctx.configString("url");

        if (url == null || url.isBlank()) {
            return NodeResult.fail("Upload File has no URL configured.");
        }

        URI uri;

        try {
            uri = com.flowops.common.security.SsrfGuard.validate(url);
        } catch (IllegalArgumentException badUrl) {
            return NodeResult.fail(
                    "Upload File URL is not a valid or allowed URI."
            );
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail(
                    "Upload File URL rejected: " + blocked.getMessage()
            );
        }

        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                || uri.getScheme().equals("https"))) {
            return NodeResult.fail(
                    "Upload File URL must be http or https."
            );
        }

        byte[] payload = readPayload(ctx);

        if (payload == null) {
            return NodeResult.fail(
                    "Upload File has no filePath or inlineContent configured."
            );
        }

        String fileName =
                orDefault(
                        ctx.configString("fileName"),
                        "upload"
                );

        String partContentType =
                orDefault(
                        ctx.configString("contentType"),
                        "application/octet-stream"
                );

        String method =
                orDefault(
                        ctx.configString("method"),
                        "POST"
                ).toUpperCase(Locale.ROOT);

        String boundary =
                "----FlowOpsMultipart"
                        + Long.toHexString(System.nanoTime());

        String multipartBody =
                buildMultipart(
                        ctx,
                        boundary,
                        fileName,
                        partContentType,
                        payload
                );

        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(
                                ctx.properties().httpRequestTimeout()
                        )
                        .header(
                                "Content-Type",
                                "multipart/form-data; boundary=" + boundary
                        )
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(
                                        multipartBody,
                                        StandardCharsets.UTF_8
                                )
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

        int status = response.statusCode();

        ObjectNode output =
                ctx.mapper().createObjectNode();

        output.put("status", status);

        output.put(
                "ok",
                status >= 200 && status < 300
        );

        output.put(
                "contentType",
                response.headers()
                        .firstValue("content-type")
                        .orElse("")
        );

        output.put(
                "formFieldName",
                "file"
        );

        output.put(
                "fileName",
                fileName
        );

        output.set(
                "body",
                parseBody(ctx, response)
        );

        ctx.log().info(
                "Upload "
                        + method
                        + " "
                        + safeTarget(uri)
                        + " → "
                        + status
                        + " ("
                        + millis
                        + "ms)"
        );

        return NodeResult.success(output);
    }

    /**
     * Reads the file from disk or uses inline content as UTF-8 bytes;
     * null when neither is given.
     */
    private byte[] readPayload(NodeExecutionContext ctx) {
        String filePath = ctx.configString("filePath");

        if (filePath != null && !filePath.isBlank()) {

            try {
                Path base = Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "flowops-downloads"
                ).toAbsolutePath().normalize();

                Path absolute = base.resolve(filePath.trim()).normalize();

                if (!absolute.startsWith(base) || absolute.equals(base)) {
                    ctx.log().warn(
                            "Upload filePath \""
                                    + filePath
                                    + "\" is outside the allowed directory and was ignored."
                    );
                    return null;
                }

                if (!Files.exists(absolute) || !Files.isRegularFile(absolute)) {
                    ctx.log().warn(
                            "Upload filePath \""
                                    + filePath
                                    + "\" does not exist or is not a regular file."
                    );
                    return null;
                }

                return Files.readAllBytes(absolute);

            } catch (Exception e) {

                ctx.log().warn(
                        "Upload could not read filePath \""
                                + filePath
                                + "\": "
                                + sanitize(e)
                );

                return null;
            }
        }

        String inline =
                ctx.configString("inlineContent");

        if (inline == null || inline.isBlank()) {
            return null;
        }

        return inline.getBytes(
                StandardCharsets.UTF_8
        );
    }

    private String buildMultipart(
            NodeExecutionContext ctx,
            String boundary,
            String fileName,
            String partContentType,
            byte[] payload
    ) {

        StringBuilder body =
                new StringBuilder();

        body.append("--")
                .append(boundary)
                .append(CRLF);

        body.append(
                "Content-Disposition: form-data; name=\"file\"; filename=\""
        )
                .append(fileName)
                .append("\"")
                .append(CRLF);

        body.append("Content-Type: ")
                .append(partContentType)
                .append(CRLF);

        body.append(CRLF);

        body.append(
                new String(
                        payload,
                        StandardCharsets.UTF_8
                )
        );

        body.append(CRLF);

        Object rawFields =
                ctx.config().get("fields");

        Map<String, Object> fields =
                asMap(
                        ctx,
                        rawFields
                );

        for (Map.Entry<String, Object> entry
                : fields.entrySet()) {

            if (entry.getKey() == null
                    || entry.getValue() == null) {
                continue;
            }

            body.append("--")
                    .append(boundary)
                    .append(CRLF);

            body.append(
                    "Content-Disposition: form-data; name=\""
            )
                    .append(entry.getKey())
                    .append("\"")
                    .append(CRLF);

            body.append(CRLF);

            body.append(
                    ctx.interpolate(
                            String.valueOf(
                                    entry.getValue()
                            )
                    )
            );

            body.append(CRLF);
        }

        body.append("--")
                .append(boundary)
                .append("--")
                .append(CRLF);

        return body.toString();
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