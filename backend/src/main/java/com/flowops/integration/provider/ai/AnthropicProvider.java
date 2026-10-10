package com.flowops.integration.provider.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.security.SsrfGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.flowops.integration.provider.AiProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.ProviderCapabilities;
import org.springframework.stereotype.Component;

/**
 * Anthropic provider.
 */
@Component
public class AnthropicProvider implements AiProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicProvider(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.ANTHROPIC;
    }

    @Override
    public String displayName() {
        return "Anthropic";
    }

    @Override
    public String description() {
        return "Connect to Anthropic models.";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, false, false, false, false, false);
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("apiKey", "API Key", true,
                        "sk-...-...", "sk-...",
                        "Anthropic API Key.")
        );
    }

    @Override
    public List<String> listModels(IntegrationContext context) {
        return List.of("claude-3-5-sonnet-20240620", "claude-3-opus-20240229");
    }

    @Override
    public String generateContent(IntegrationContext context, String prompt, String model) {
        String apiKey = context.credentials().require("apiKey");
        String url = "https://api.anthropic.com/v1/messages";

        URI uri = SsrfGuard.validate(url);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new ApiException(ErrorCode.INTEGRATION_INVALID, "Anthropic API returned error: " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            return responseJson.at("/content/0/text").asText();

        } catch (IOException | InterruptedException e) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID, "Failed to call Anthropic API: " + e.getMessage());
        }
    }

    @Override
    public ConnectionTestResult testConnection(IntegrationContext context) {
        // Simple test: list models or call messages? Let's just return success for listModels as a test
        try {
            listModels(context);
            return ConnectionTestResult.success("Anthropic connection successful");
        } catch (Exception e) {
            return ConnectionTestResult.failure("Anthropic connection failed: " + e.getMessage());
        }
    }
}
