package com.flowops.integration.delivery.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.delivery.support.HmacSigner;
import com.flowops.integration.delivery.support.WebhookDeliverer;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Outbound webhook: FlowOps POSTs events to any HTTPS endpoint you own. This provider
 * does double duty:
 * <ul>
 *   <li>as a <em>delivery channel</em> for a workflow's notification node, and</li>
 *   <li>as the <em>event sink</em> the {@code WebhookEventDispatcher} targets when an
 *       execution starts/completes/fails, an anomaly is detected, or a workflow is
 *       published — filtered by the configured {@code eventTypes} list.</li>
 * </ul>
 * Configured headers, basic/bearer auth, and the optional HMAC secret are all stored
 * encrypted and never revealed. When a {@code secret} is set, every request carries an
 * {@code X-Flowops-Signature: sha256=<hmac>} header over the exact request body so the
 * receiver can authenticate the payload. Headers may contain secrets, so the connect
 * form marks them sensitive; the URL itself is non-secret display config.
 */
@Component
public class WebhookProvider implements NotificationProvider {

    /** Header name used for HMAC-signed outbound events. */
    public static final String SIGNATURE_HEADER = "X-Flowops-Signature";

    private static final String TEST_EVENT_TYPE = "test";

    private final WebhookDeliverer deliverer;
    private final HmacSigner signer;
    private final ObjectMapper mapper;

    public WebhookProvider(WebhookDeliverer deliverer, HmacSigner signer, ObjectMapper mapper) {
        this.deliverer = deliverer;
        this.signer = signer;
        this.mapper = mapper;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.WEBHOOK;
    }

    @Override
    public String displayName() {
        return "Outbound webhook";
    }

    @Override
    public String description() {
        return "Deliver Vantis events to any HTTPS endpoint with retries, signing, "
                + "and per-event delivery status.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("url", "Endpoint URL", false,
                        "https://example.com/hooks/vantis",
                        "https://example.com/hooks/vantis",
                        "Vantis POSTs JSON events here"),
                new CredentialField("method", "Method", false, "POST", "POST",
                        "Only POST is supported (JSON body)"),
                new CredentialField("headers", "Custom headers (JSON)", true,
                        "{\"X-Api-Key\": \"…\"}", "{\"X-Api-Key\": \"…\"}",
                        "Optional extra headers. May contain secrets — stored encrypted."),
                new CredentialField("authType", "Auth type", false,
                        "none", "none / bearer / basic",
                        "Bearer or basic auth, if the endpoint requires it"),
                new CredentialField("authUsername", "Username (basic)", false,
                        "", "", "Optional basic-auth username"),
                new CredentialField("authPassword", "Password / bearer token", true,
                        "••••••••", "••••••••",
                        "Bearer token (when authType=bearer) or basic-auth password."),
                new CredentialField("secret", "Signing secret", true,
                        "••••••••", "••••••••",
                        "When set, every request carries X-Flowops-Signature = sha256 HMAC."),
                new CredentialField("eventTypes", "Event types", false,
                        "execution.completed,execution.failed,anomaly.detected",
                        "execution.started,execution.completed,execution.failed,"
                                + "anomaly.detected,workflow.published",
                        "Comma-separated events to auto-dispatch to this URL"),
                new CredentialField("timeoutSeconds", "Timeout (seconds)", false,
                        "30", "30", "Per-request timeout"),
                new CredentialField("retries", "Retries", false, "2", "2",
                        "Extra attempts the event dispatcher makes on transient failures"));
    }

    @Override
    public String secretKey() {
        return "secret";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        Long timeout = timeoutSeconds(context);
        if (timeout != null && timeout <= 0) {
            timeout = null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", TEST_EVENT_TYPE);
        payload.put("source", "flowops");
        payload.put("message", "Vantis connection test — if you can read this, "
                + "your webhook integration works.");
        DeliveryResult result = send(context, payload, timeoutMillis(timeout));
        if (result.success()) {
            return ConnectionTestResult.success(
                    "Webhook accepted the request (HTTP " + result.httpStatus() + ").");
        }
        return ConnectionTestResult.failure(result.message());
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "notification");
        payload.put("channel", message.channel());
        payload.put("message", message.text());
        if (message.payload() != null) {
            message.payload().forEach((k, v) -> {
                if (!payload.containsKey(k)) {
                    payload.put(k, v);
                }
            });
        }
        Long timeout = timeoutSeconds(context);
        return send(context, payload, timeoutMillis(timeout));
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", TEST_EVENT_TYPE);
        payload.put("source", "flowops");
        payload.put("message", "Vantis test event");
        Long timeout = timeoutSeconds(context);
        return send(context, payload, timeoutMillis(timeout));
    }

    /**
     * Dispatches a Vantis event to the configured endpoint (used by
     * {@code WebhookEventDispatcher}). The envelope always carries {@code event},
     * {@code timestamp}, and {@code source}, and is HMAC-signed when a secret is set.
     */
    public DeliveryResult dispatchEvent(
            DeliveryContext context, String eventType, Map<String, Object> eventPayload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventType);
        envelope.put("timestamp", java.time.Instant.now().toString());
        envelope.put("source", "flowops");
        Map<String, Object> data = new LinkedHashMap<>(eventPayload == null ? Map.of() : eventPayload);
        envelope.put("data", data);
        Long timeout = timeoutSeconds(context);
        return send(context, envelope, timeoutMillis(timeout));
    }

    private DeliveryResult send(
            DeliveryContext context, Map<String, Object> payload, Duration requestTimeout) {
        String url = requireUrl(context);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The webhook integration has no endpoint URL.", false, null, 0);
        }
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (IOException serializationFailed) {
            return DeliveryResult.failure(DeliveryResult.Code.SEND_FAILED,
                    "The webhook payload could not be serialized.", false, null, 0);
        }
        Map<String, String> headers = buildHeaders(context, body);
        return deliverer.post(url, headers, body, requestTimeout);
    }

    private Map<String, String> buildHeaders(DeliveryContext context, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        String rawHeaders = context.credentials().get("headers");
        if (rawHeaders != null && !rawHeaders.isBlank()) {
            try {
                Map<String, Object> parsed = mapper.readValue(
                        rawHeaders, new com.fasterxml.jackson.core.type.TypeReference<>() {
                        });
                parsed.forEach((k, v) -> {
                    if (k != null && v != null) {
                        headers.put(k, String.valueOf(v));
                    }
                });
            } catch (IOException malformed) {
                // Non-secret fallback — headers are ignored, never echoed.
                headers.put("X-Flowops-Error", "malformed headers config");
            }
        }
        String authType = cred(context, "authType");
        if ("bearer".equalsIgnoreCase(authType)) {
            String token = cred(context, "authPassword");
            if (token != null && !token.isBlank()) {
                headers.put("Authorization", "Bearer " + token);
            }
        } else if ("basic".equalsIgnoreCase(authType)) {
            String user = cred(context, "authUsername");
            String pass = cred(context, "authPassword");
            if (user != null && pass != null) {
                String encoded = Base64.getEncoder().encodeToString(
                        (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
        }
        String secret = cred(context, "secret");
        if (secret != null && !secret.isBlank()) {
            headers.put(SIGNATURE_HEADER, signer.headerValue(signer.sign(secret, body)));
        }
        return headers;
    }

    private static String requireUrl(DeliveryContext context) {
        String url = context.credentials().get("url");
        return (url == null || url.isBlank()) ? null : url;
    }

    private static Duration timeoutMillis(Long seconds) {
        return seconds == null ? null : Duration.ofSeconds(seconds);
    }

    private static Long timeoutSeconds(DeliveryContext context) {
        String raw = cred(context, "timeoutSeconds");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String cred(DeliveryContext context, String key) {
        return context.credentials().get(key);
    }
}