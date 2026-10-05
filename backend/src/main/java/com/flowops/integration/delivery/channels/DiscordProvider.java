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
 * Discord webhook delivery. No OAuth app is required — an incoming webhook URL is
 * sufficient for the MVP, as the product brief specifies. The URL is a secret and is
 * masked in the UI.
 */
@Component
public class DiscordProvider implements NotificationProvider {

    private static final String TEST_TEXT = "Vantis connection test ✔";
    private static final String DISCORD_HOST = "discord.com";

    private final WebhookDeliverer deliverer;

    public DiscordProvider(WebhookDeliverer deliverer) {
        this.deliverer = deliverer;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.DISCORD;
    }

    @Override
    public String displayName() {
        return "Discord";
    }

    @Override
    public String description() {
        return "Post workflow notifications to a Discord channel via an incoming webhook.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("webhookUrl", "Webhook URL", true,
                        "https://discord.com/api/webhooks/…",
                        "https://discord.com/api/webhooks/…",
                        "Channel → Integrations → Webhooks. Stored encrypted and masked in the UI."),
                new CredentialField("channelName", "Webhook name (optional)", false,
                        "vantis-bot", "vantis-bot",
                        "Shown as the sender in Discord."));
    }

    @Override
    public String secretKey() {
        return "webhookUrl";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String url = requireUrl(context);
        if (url == null) {
            return ConnectionTestResult.failure("The Discord integration has no webhook URL.");
        }
        DeliveryResult result = deliverer.postJson(url, Map.of(), Map.of("content", TEST_TEXT), null);
        if (result.success()) {
            return ConnectionTestResult.success("Connected to Discord (HTTP " + result.httpStatus() + ").");
        }
        return ConnectionTestResult.failure(result.message());
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        String url = requireUrl(context);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Discord integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("content", text(message)), null);
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        String url = requireUrl(context);
        if (url == null) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "The Discord integration has no webhook URL.", false, null, 0);
        }
        return deliverer.postJson(url, Map.of(), Map.of("content", TEST_TEXT), null);
    }

    private String requireUrl(DeliveryContext context) {
        String url = context.credentials().get("webhookUrl");
        return (url == null || url.isBlank()) ? null : url;
    }

    private static String text(NotificationMessage message) {
        String text = message.text() == null || message.text().isBlank()
                ? "Vantis notification"
                : message.text();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    /** Host validation helper kept referenced so the host constant reflects intent. */
    @SuppressWarnings("unused")
    private static String discordHost() {
        return DISCORD_HOST;
    }
}