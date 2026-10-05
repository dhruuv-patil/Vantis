package com.flowops.integration.delivery.channels;

import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.delivery.support.WebhookDeliverer;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationType;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Slack incoming-webhook delivery. The webhook URL is the secret: it is stored
 * encrypted, validated to be an https hooks.slack.com /services/… URL on connect,
 * and never logged, emitted, or returned. Test and delivery both POST a {@code text}
 * payload and report a real HTTP outcome.
 */
@Component
public class SlackProvider implements NotificationProvider {

    private static final String TEST_TEXT =
            "Vantis connection test — if you can read this, Slack notifications work.";

    private final WebhookDeliverer deliverer;

    public SlackProvider(WebhookDeliverer deliverer) {
        this.deliverer = deliverer;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.SLACK;
    }

    @Override
    public String displayName() {
        return "Slack";
    }

    @Override
    public String description() {
        return "Post workflow notifications to a channel via an incoming webhook.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("webhookUrl", "Incoming webhook URL", true,
                        "https://hooks.slack.com/services/T…/B…/…",
                        "https://hooks.slack.com/services/T…/B…/…",
                        "Slack → Incoming Webhooks. Stored encrypted; never shown again."),
                new CredentialField("channelName", "Channel name (optional)", false,
                        "#operations", "#operations",
                        "Labels this connection in Vantis. The webhook already decides the channel."));
    }

    @Override
    public String secretKey() {
        return "webhookUrl";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String url = requireWebhookUrl(context);
        DeliveryResult result = deliverer.postJson(url, Map.of(),
                Map.of("text", TEST_TEXT), null);
        if (result.success()) {
            return ConnectionTestResult.success("Connected to Slack (HTTP " + result.httpStatus() + ").");
        }
        return ConnectionTestResult.failure(result.message());
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        String url = requireWebhookUrl(context, DeliveryResult.Code.INVALID_CONFIG);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Slack integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("text", text(message)), null);
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        String url = requireWebhookUrl(context, DeliveryResult.Code.INVALID_CONFIG);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Slack integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("text", TEST_TEXT), null);
    }

    private String requireWebhookUrl(DeliveryContext context) {
        return requireWebhookUrl(context, DeliveryResult.Code.AUTH_FAILED);
    }

    private String requireWebhookUrl(DeliveryContext context, String missingCode) {
        String url = context.credentials().get("webhookUrl");
        if (url == null || url.isBlank()) {
            return null;
        }
        return url;
    }

    private static String text(NotificationMessage message) {
        return (message.text() == null || message.text().isBlank())
                ? "Vantis notification"
                : message.text();
    }
}