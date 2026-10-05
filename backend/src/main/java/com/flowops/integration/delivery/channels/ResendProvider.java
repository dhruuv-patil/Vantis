package com.flowops.integration.delivery.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.ProviderCapabilities;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ResendProvider implements NotificationProvider {

    private static final String API_BASE_URL = "https://api.resend.com";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public ResendProvider() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                API_BASE_URL);
    }

    ResendProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String apiBaseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.RESEND;
    }

    @Override
    public String displayName() {
        return "Resend";
    }

    @Override
    public String description() {
        return "Send transactional email through Resend.";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                false,
                false,
                false,
                false,
                true,
                false);
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField(
                        "apiKey",
                        "API Key",
                        true,
                        "re_...",
                        "re_...",
                        "Resend API Key"),
                new CredentialField(
                        "fromEmail",
                        "From Email",
                        true,
                        "noreply@example.com",
                        "noreply@example.com",
                        "Verified sender email address"));
    }

    @Override
    public String secretKey() {
        return "apiKey";
    }

    @Override
    public ConnectionTestResult testConnection(
            DeliveryContext context) {
        String apiKey = getCredential(context, "apiKey");

        if (isBlank(apiKey)) {
            return ConnectionTestResult.failure(
                    "Missing API Key");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/domains"))
                    .timeout(REQUEST_TIMEOUT)
                    .header(
                            "Authorization",
                            "Bearer " + apiKey)
                    .header(
                            "Accept",
                            "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return ConnectionTestResult.success(
                        "Connected to Resend");
            }

            if (status == 401 || status == 403) {
                return ConnectionTestResult.failure(
                        "Resend authentication failed");
            }

            if (status == 429) {
                return ConnectionTestResult.failure(
                        "Resend rate limit exceeded");
            }

            return ConnectionTestResult.failure(
                    "Resend connection test failed");

        } catch (java.net.http.HttpTimeoutException e) {

            return ConnectionTestResult.failure(
                    "Resend connection timed out");

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            return ConnectionTestResult.failure(
                    "Resend connection test interrupted");

        } catch (IOException e) {

            return ConnectionTestResult.failure(
                    "Unable to connect to Resend");
        }
    }

    @Override
    public DeliveryResult deliver(
            DeliveryContext context,
            NotificationMessage message) {

        long started = System.nanoTime();

        if (message == null) {
            return failure(
                    DeliveryResult.Code.INVALID_CONFIG,
                    "Notification message is missing",
                    false,
                    null,
                    started);
        }

        String apiKey = getCredential(context, "apiKey");

        if (isBlank(apiKey)) {
            return failure(
                    DeliveryResult.Code.INVALID_CONFIG,
                    "Resend API Key is not configured",
                    false,
                    null,
                    started);
        }

        String fromEmail = getCredential(context, "fromEmail");

        if (isBlank(fromEmail)) {
            return failure(
                    DeliveryResult.Code.INVALID_CONFIG,
                    "Resend sender email is not configured",
                    false,
                    null,
                    started);
        }

        String toEmail = resolveRecipient(message);

        if (isBlank(toEmail)) {
            return failure(
                    DeliveryResult.Code.INVALID_CONFIG,
                    "Resend recipient email is not configured",
                    false,
                    null,
                    started);
        }

        if (isBlank(message.text())) {
            return failure(
                    DeliveryResult.Code.INVALID_CONFIG,
                    "Notification message is empty",
                    false,
                    null,
                    started);
        }

        String subject = resolveSubject(message);

        try {

            Map<String, Object> requestBody = Map.of(
                    "from",
                    fromEmail,
                    "to",
                    List.of(toEmail),
                    "subject",
                    subject,
                    "text",
                    message.text());

            String json = objectMapper.writeValueAsString(
                    requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(
                            URI.create(
                                    apiBaseUrl + "/emails"))
                    .timeout(REQUEST_TIMEOUT)
                    .header(
                            "Authorization",
                            "Bearer " + apiKey)
                    .header(
                            "Content-Type",
                            "application/json")
                    .header(
                            "Accept",
                            "application/json")
                    .POST(
                            HttpRequest.BodyPublishers
                                    .ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status >= 200 && status < 300) {

                if (!hasResponseId(response.body())) {
                    return failure(
                            DeliveryResult.Code.SEND_FAILED,
                            "Resend returned an invalid response",
                            false,
                            status,
                            started);
                }

                return DeliveryResult.success(
                        status,
                        elapsedMs(started));
            }

            if (status == 401 || status == 403) {
                return failure(
                        DeliveryResult.Code.AUTH_FAILED,
                        "Resend authentication failed",
                        false,
                        status,
                        started);
            }

            if (status == 429) {
                return failure(
                        DeliveryResult.Code.RATE_LIMITED,
                        "Resend rate limit exceeded",
                        true,
                        status,
                        started);
            }

            if (status >= 500) {
                return failure(
                        DeliveryResult.Code.SEND_FAILED,
                        "Resend is temporarily unavailable",
                        true,
                        status,
                        started);
            }

            return failure(
                    DeliveryResult.Code.SEND_FAILED,
                    "Resend rejected the email request",
                    false,
                    status,
                    started);

        } catch (java.net.http.HttpTimeoutException e) {

            return failure(
                    DeliveryResult.Code.CONNECTION_TIMEOUT,
                    "Resend request timed out",
                    true,
                    null,
                    started);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            return failure(
                    DeliveryResult.Code.NETWORK_ERROR,
                    "Resend request interrupted",
                    true,
                    null,
                    started);

        } catch (IOException e) {

            return failure(
                    DeliveryResult.Code.NETWORK_ERROR,
                    "Unable to communicate with Resend",
                    true,
                    null,
                    started);
        }
    }

    @Override
    public DeliveryResult sendTest(
            DeliveryContext context) {

        return deliver(
                context,
                new NotificationMessage(
                        "resend-test",
                        "Vantis Resend integration test.",
                        Map.of(
                                "to",
                                getCredential(
                                        context,
                                        "fromEmail"),
                                "subject",
                                "Vantis Resend Test")));
    }

    private String resolveRecipient(
            NotificationMessage message) {

        if (message.payload() == null) {
            return null;
        }

        Object to = message.payload().get("to");

        if (to == null) {
            to = message.payload().get("toEmail");
        }

        if (to == null) {
            to = message.payload().get("toAddress");
        }

        return to == null
                ? null
                : to.toString().trim();
    }

    private String resolveSubject(
            NotificationMessage message) {

        if (message.payload() != null) {

            Object subject = message.payload().get("subject");

            if (subject != null &&
                    !subject.toString().isBlank()) {

                return subject.toString().trim();
            }
        }

        return "Vantis Notification";
    }

    private boolean hasResponseId(
            String responseBody) {

        if (isBlank(responseBody)) {
            return false;
        }

        try {

            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode id = root.get("id");

            return id != null
                    && id.isTextual()
                    && !id.asText().isBlank();

        } catch (Exception e) {
            return false;
        }
    }

    private String getCredential(
            DeliveryContext context,
            String key) {

        if (context == null ||
                context.credentials() == null) {
            return null;
        }

        return context.credentials().get(key);
    }

    private DeliveryResult failure(
            String code,
            String message,
            boolean retryable,
            Integer httpStatus,
            long started) {

        return DeliveryResult.failure(
                code,
                message,
                retryable,
                httpStatus,
                elapsedMs(started));
    }

    private long elapsedMs(long started) {

        return Duration.ofNanos(
                System.nanoTime() - started).toMillis();
    }

    private boolean isBlank(String value) {

        return value == null ||
                value.isBlank();
    }
}