package com.flowops.common.security.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable request model for outbound hardened HTTP requests.
 */
public final class HardenedHttpRequest {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final byte[] body;
    private final boolean followRedirects;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private HardenedHttpRequest(Builder builder) {
        this.method = builder.method;
        this.url = builder.url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.body = builder.body;
        this.followRedirects = builder.followRedirects;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public boolean followRedirects() {
        return followRedirects;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public static class Builder {
        private String method = "GET";
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private boolean followRedirects = false;
        private Duration connectTimeout;
        private Duration readTimeout;

        public Builder get(String url) {
            this.method = "GET";
            this.url = url;
            return this;
        }

        public Builder post(String url) {
            this.method = "POST";
            this.url = url;
            return this;
        }

        public Builder post(String url, String body, String contentType) {
            this.method = "POST";
            this.url = url;
            this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
            if (contentType != null) {
                this.headers.put("Content-Type", contentType);
            }
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.connectTimeout = timeout;
            this.readTimeout = timeout;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder body(String stringBody) {
            this.body = stringBody != null ? stringBody.getBytes(StandardCharsets.UTF_8) : null;
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public HardenedHttpRequest build() {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("URL must be specified");
            }
            return new HardenedHttpRequest(this);
        }
    }
}
