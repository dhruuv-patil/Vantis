package com.flowops.integration.provider.n8n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.ProviderAuthenticationException;
import com.flowops.integration.provider.ProviderConfigurationException;
import com.flowops.integration.provider.ProviderConnectionException;
import com.flowops.integration.provider.ProviderRateLimitException;
import com.flowops.integration.provider.ProviderResponseException;
import com.flowops.integration.provider.ProviderUnavailableException;
import com.flowops.common.security.http.HardenedHttpClient;
import com.flowops.common.security.http.HardenedHttpException;
import com.flowops.common.security.http.HardenedHttpRequest;
import com.flowops.common.security.http.HardenedHttpResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for n8n REST API v1.
 *
 * <p>Mirrors {@code AiServiceClient} / {@code HttpRequestExecutor}:
 * connect timeout + request timeout.</p>
 *
 * <p>Authentication credentials are never written to logs.</p>
 */
@Component
public class N8nClient {

    private static final Logger log =
            LoggerFactory.getLogger(N8nClient.class);

    private static final Duration CONNECT_TIMEOUT =
            Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(30);

    private final HardenedHttpClient httpClient;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public N8nClient(HardenedHttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public N8nClient(ObjectMapper mapper) {
        this(createDefaultClient(mapper), mapper);
    }

    private static HardenedHttpClient createDefaultClient(ObjectMapper mapper) {
        com.flowops.common.security.http.SsrfProperties props = new com.flowops.common.security.http.SsrfProperties();
        props.getAllowlist().setEnabled(true);
        props.getAllowlist().getAllowedHosts().add("localhost");
        props.getAllowlist().getAllowedHosts().add("127.0.0.1");
        props.getAllowlist().getAllowedCidrs().add("127.0.0.0/8");
        props.getAllowedPorts().clear();
        com.flowops.common.security.http.IpAddressValidator ipVal = new com.flowops.common.security.http.IpAddressValidator(props);
        com.flowops.common.security.http.UrlValidator urlVal = new com.flowops.common.security.http.UrlValidator(props, ipVal);
        return new HardenedHttpClient(props, urlVal, new com.flowops.common.security.http.SystemDnsResolver(), ipVal, mapper);
    }

    /**
     * Lists workflows from n8n.
     *
     * GET /api/v1/workflows?limit={limit}
     */
    public JsonNode listWorkflows(
            IntegrationContext context,
            int limit) {

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "Workflow limit must be greater than zero");
        }

        String baseUrl = requireBaseUrl(context);
        String apiKey = requireApiKey(context);

        String url = baseUrl
                + "/api/v1/workflows?limit="
                + limit;

        return get(url, apiKey);
    }

    /**
     * Lists executions from n8n.
     *
     * First page:
     *
     * GET /api/v1/executions?limit={limit}&includeData=false
     *
     * Subsequent pages:
     *
     * GET /api/v1/executions?limit={limit}&includeData=false&cursor={cursor}
     *
     * <p>The cursor is opaque and must be passed back unchanged after
     * URL encoding. It must not be replaced with the last execution ID.</p>
     */
    public JsonNode listExecutions(
            IntegrationContext context,
            int limit,
            com.flowops.integration.provider.SyncCursor cursor) {

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "Execution limit must be greater than zero");
        }

        String baseUrl = requireBaseUrl(context);
        String apiKey = requireApiKey(context);

        StringBuilder url = new StringBuilder(
                baseUrl + "/api/v1/executions")
                .append("?limit=")
                .append(limit)
                .append("&includeData=false");

        if (cursor != null && !cursor.isEmpty()) {
            String encodedCursor = URLEncoder.encode(
                    cursor.value(),
                    StandardCharsets.UTF_8);

            url.append("&cursor=")
                    .append(encodedCursor);
        }

        return get(url.toString(), apiKey);
    }

    /**
     * Gets execution data including node results from n8n.
     *
     * GET /api/v1/executions/{id}?includeData=true
     */
    public JsonNode getExecutionData(
            IntegrationContext context,
            String executionId) {

        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Execution ID must not be blank");
        }

        String baseUrl = requireBaseUrl(context);
        String apiKey = requireApiKey(context);

        String encodedExecutionId = URLEncoder.encode(
                executionId,
                StandardCharsets.UTF_8);

        String url = baseUrl
                + "/api/v1/executions/"
                + encodedExecutionId
                + "?includeData=true";

        return get(url, apiKey);
    }

    /**
     * Executes an authenticated GET request using the centralized hardened client.
     */
    private JsonNode get(
            String url,
            String apiKey) {

        HardenedHttpRequest request = HardenedHttpRequest.builder()
                .get(url)
                .header("Accept", "application/json")
                .header("X-N8N-API-KEY", apiKey)
                .readTimeout(REQUEST_TIMEOUT)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        try {
            HardenedHttpResponse response = httpClient.execute(request);
            return handleResponse(response);
        } catch (HardenedHttpException e) {
            log.warn("n8n HTTP request failed: {}", e.getMessage());
            throw new ProviderConnectionException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Connection failed: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Handles an n8n HTTP response.
     *
     * <p>For client errors, the response body is included in the exception
     * so that n8n's actual validation error is visible to FlowOps. The
     * authentication header and API key are never logged.</p>
     */
    private JsonNode handleResponse(
            HardenedHttpResponse response) {

        int status = response.statusCode();
        String body = response.bodyAsString();

        if (status >= 200 && status < 300) {
            try {
                if (body == null || body.isBlank()) {
                    return mapper.createObjectNode();
                }

                return mapper.readTree(body);

            } catch (IOException e) {
                throw new ProviderResponseException(
                        com.flowops.integration.provider.IntegrationType.N8N,
                        status,
                        "Failed to parse response: "
                                + e.getMessage(),
                        e);
            }
        }

        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Authentication failed (HTTP "
                            + status
                            + "): "
                            + sanitizeResponseBody(body));
        }

        if (status == 404) {
            throw new ProviderConfigurationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Not found - check base URL and API endpoint "
                            + "(HTTP 404): "
                            + sanitizeResponseBody(body));
        }

        if (status == 429) {
            long retryAfter = 60;

            try {
                String retryHeader =
                        response.header("Retry-After")
                                .orElse("60");

                retryAfter = Long.parseLong(retryHeader);

            } catch (NumberFormatException ignored) {
                // Keep the default retry delay.
            }

            throw new ProviderRateLimitException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Rate limited (HTTP 429): "
                            + sanitizeResponseBody(body),
                    retryAfter);
        }

        if (status >= 500) {
            throw new ProviderUnavailableException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Provider error (HTTP "
                            + status
                            + "): "
                            + sanitizeResponseBody(body));
        }

        String responseBody = sanitizeResponseBody(body);

        log.warn(
                "n8n API request failed: status={}, responseBody={}",
                status,
                responseBody);

        throw new ProviderResponseException(
                com.flowops.integration.provider.IntegrationType.N8N,
                status,
                "Request failed (HTTP "
                        + status
                        + "): "
                        + responseBody);
    }

    /**
     * Prevents excessively large provider error bodies from flooding logs
     * or exception messages.
     */
    private String sanitizeResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "<empty response body>";
        }

        String normalized = body
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();

        int maxLength = 2000;

        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength)
                + "...";
    }

    private String requireBaseUrl(
            IntegrationContext context) {

        if (context == null || context.credentials() == null) {
            throw new ProviderConfigurationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Missing n8n credentials");
        }

        DecryptedCredentials creds =
                context.credentials();

        String baseUrl = creds.get("baseUrl");

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ProviderConfigurationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Missing baseUrl in credentials");
        }

        return baseUrl.replaceAll("/+$", "");
    }

    private String requireApiKey(
            IntegrationContext context) {

        if (context == null || context.credentials() == null) {
            throw new ProviderAuthenticationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Missing n8n credentials");
        }

        String apiKey =
                context.credentials().get("apiKey");

        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderAuthenticationException(
                    com.flowops.integration.provider.IntegrationType.N8N,
                    "Missing apiKey in credentials");
        }

        return apiKey;
    }
}