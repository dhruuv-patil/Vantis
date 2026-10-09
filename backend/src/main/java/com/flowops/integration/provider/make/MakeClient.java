package com.flowops.integration.provider.make;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.IntegrationType;
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
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the Make API v2 (formerly Integromat).
 *
 * <p>Authentication is the organization API token; the token never leaves the
 * request header and is never logged. The base URL is the region (eu1, eu2, us1,
 * us2, ca1, au1, ...).</p>
 *
 * <p>Endpoints used (mapped against Make's public REST API):</p>
 * <ul>
 *   <li>{@code GET /scenarios?limit=N} — scenario (workflow) discovery</li>
 *   <li>{@code GET /executions?limit=N} — scenario execution history</li>
 * </ul>
 *
 * <p>Make's public API does not expose a cursor for executions, so sync fetches a
 * bounded page each cycle; the idempotency key on {@code execution_events} keeps
 * re-syncs cheap and duplicate-free.</p>
 */
@Component
public class MakeClient {

    private static final Logger log = LoggerFactory.getLogger(MakeClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_BASE_URL = "https://eu1.make.com";

    private final HardenedHttpClient httpClient;
    private final ObjectMapper mapper;

    public MakeClient(HardenedHttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public JsonNode listScenarios(IntegrationContext context, int limit) {
        return get(context, "/api/v2/scenarios?limit=" + limit);
    }

    public JsonNode listExecutions(IntegrationContext context, int limit) {
        return get(context, "/api/v2/executions?limit=" + limit);
    }

    private JsonNode get(IntegrationContext context, String path) {
        String baseUrl = baseUrl(context);
        String token = token(context);
        HardenedHttpRequest request = HardenedHttpRequest.builder()
                .get(baseUrl + path)
                .header("Accept", "application/json")
                .header("Authorization", "Token " + token)
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(REQUEST_TIMEOUT)
                .build();
        try {
            HardenedHttpResponse response = httpClient.execute(request);
            return handleResponse(response);
        } catch (HardenedHttpException e) {
            log.warn("Make HTTP request failed: {}", e.getMessage());
            throw new ProviderConnectionException(
                    IntegrationType.MAKE, "Connection failed: " + e.getMessage(), e);
        }
    }

    private JsonNode handleResponse(HardenedHttpResponse response) {
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
                        IntegrationType.MAKE, status, "Failed to parse response: " + e.getMessage(), e);
            }
        }
        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException(
                    IntegrationType.MAKE, "Authentication failed (HTTP " + status + ").");
        }
        if (status == 404) {
            throw new ProviderConfigurationException(
                    IntegrationType.MAKE,
                    "Not found — check the Make region base URL (HTTP 404).");
        }
        if (status == 429) {
            long retryAfter = 60;
            try {
                retryAfter = Long.parseLong(response.header("Retry-After").orElse("60"));
            } catch (NumberFormatException ignored) {}
            throw new ProviderRateLimitException(
                    IntegrationType.MAKE, "Rate limited (HTTP 429).", retryAfter);
        }
        if (status >= 500) {
            throw new ProviderUnavailableException(
                    IntegrationType.MAKE, "Provider error (HTTP " + status + ").");
        }
        throw new ProviderResponseException(
                IntegrationType.MAKE, status, "Request failed (HTTP " + status + ").");
    }

    private String baseUrl(IntegrationContext context) {
        if (context == null || context.credentials() == null) {
            throw new ProviderConfigurationException(IntegrationType.MAKE, "Missing Make credentials");
        }
        DecryptedCredentials creds = context.credentials();
        String base = creds.get("baseUrl");
        if (base == null || base.isBlank()) {
            base = DEFAULT_BASE_URL;
        }
        return base.replaceAll("/+$", "");
    }

    private String token(IntegrationContext context) {
        if (context == null || context.credentials() == null) {
            throw new ProviderAuthenticationException(IntegrationType.MAKE, "Missing Make credentials");
        }
        String token = context.credentials().get("apiToken");
        if (token == null || token.isBlank()) {
            throw new ProviderAuthenticationException(IntegrationType.MAKE, "Missing apiToken in credentials");
        }
        return token;
    }
}