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
 * Microsoft Teams delivery via an incoming webhook (a Teams "Workflow"/connector
 * webhook URL). Sends the simple {@code {"text": …}} payload accepted by both the
 * classic Office 365 connector and the current Power Automate workflow webhooks.
 * The webhook URL is a secret, stored encrypted and masked in the UI.
 */
@Component
public class TeamsProvider implements NotificationProvider {

    private static final String TEST_TEXT =
            "Vantis connection test — if you can read this, Teams notifications work.";

    private final WebhookDeliverer deliverer;

    public TeamsProvider(WebhookDeliverer deliverer) {
        this.deliverer = deliverer;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.TEAMS;
    }

    @Override
    public String displayName() {
        return "Microsoft Teams";
    }

    @Override
    public String description() {
        return "Post workflow notifications to a Teams channel via an incoming webhook.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("webhookUrl", "Workflow webhook URL", true,
                        "https://…webhook.office.com/webhookb2/…",
                        "https://…webhook.office.com/webhookb2/…",
                        "Create a Workflow → When a webhook request is received. "
                                + "Stored encrypted; never shown again."),
                new CredentialField("channelName", "Channel name (optional)", false,
                        "#operations", "#operations",
                        "Labels this connection in Vantis."));
    }

    @Override
    public String secretKey() {
        return "webhookUrl";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String url = requireUrl(context);
        if (url == null) {
            return ConnectionTestResult.failure("The Teams integration has no webhook URL.");
        }
        DeliveryResult result = deliverer.postJson(url, Map.of(), Map.of("text", TEST_TEXT), null);
        if (result.success()) {
            return ConnectionTestResult.success("Connected to Teams (HTTP " + result.httpStatus() + ").");
        }
        return ConnectionTestResult.failure(result.message());
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        String url = requireUrl(context);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Teams integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("text", text(message)), null);
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        String url = requireUrl(context);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Teams integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("text", TEST_TEXT), null);
    }

    private String requireUrl(DeliveryContext context) {
        String url = context.credentials().get("webhookUrl");
        return (url == null || url.isBlank()) ? null : url;
    }

    private static String text(NotificationMessage message) {
        return message.text() == null || message.text().isBlank()
                ? "Vantis notification"
                : message.text();
    }
}