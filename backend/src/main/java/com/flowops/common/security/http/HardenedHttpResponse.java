package com.flowops.common.security.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Encapsulates the response from a hardened outbound HTTP call.
 */
public final class HardenedHttpResponse {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public HardenedHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body != null ? body : new byte[0];
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Optional<String> header(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return Optional.of(values.get(0));
                }
            }
        }
        return Optional.empty();
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public JsonNode json(ObjectMapper mapper) throws IOException {
        String content = bodyAsString();
        if (content.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(content);
    }

    public boolean is2xxSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isSuccess() {
        return is2xxSuccessful();
    }

    public boolean isRedirect() {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }
}
