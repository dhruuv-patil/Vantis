package com.flowops.integration.delivery.channels;

import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Telegram bot notification provider.
 */
@Component
public class TelegramProvider implements NotificationProvider {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public IntegrationType type() {
        return IntegrationType.TELEGRAM;
    }

    @Override
    public String displayName() {
        return "Telegram Bot";
    }

    @Override
    public String description() {
        return "Deliver workflow notifications to Telegram channels or chats via Bot API.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("botToken", "Bot Token", true,
                        "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ", "123456:ABC...",
                        "Obtain from @BotFather in Telegram."),
                new CredentialField("defaultChatId", "Default Chat ID", false,
                        "-100123456789", "-100123456789",
                        "Optional default chat or channel ID to send messages to.")
        );
    }

    @Override
    public String secretKey() {
        return "botToken";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String token = context.credentials().get("botToken");
        if (token == null || token.isBlank()) {
            return ConnectionTestResult.failure("Bot token is required");
        }
        try {
            URI uri = URI.create("https://api.telegram.org/bot" + token.trim() + "/getMe");
            HttpRequest req = HttpRequest.newBuilder().uri(uri).GET()
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ConnectionTestResult.success("Telegram bot verified successfully");
            }
            return ConnectionTestResult.failure("Telegram authentication failed (HTTP " + resp.statusCode() + ")");
        } catch (Exception e) {
            return ConnectionTestResult.failure("Telegram test error: " + e.getMessage());
        }
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        long start = System.currentTimeMillis();
        String token = context.credentials().get("botToken");
        if (token == null || token.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG, "Bot token missing", false, null, 0);
        }

        // Chat ID can be overridden by payload or falls back to stored default
        Object chatIdObj = message.payload() != null ? message.payload().get("chatId") : null;
        String chatId = chatIdObj != null ? chatIdObj.toString() : context.credentials().get("defaultChatId");
        if (chatId == null || chatId.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "No target Chat ID configured. Set defaultChatId in credential or chatId in payload.", false, null, 0);
        }

        try {
            URI uri = URI.create("https://api.telegram.org/bot" + token.trim() + "/sendMessage");
            String text = message.text() != null ? message.text() : "Vantis notification";
            String body = "{\"chat_id\":\"" + escape(chatId) + "\",\"text\":\"" + escape(text) + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return DeliveryResult.success(resp.statusCode(), (int) duration);
            }
            return DeliveryResult.failure(DeliveryResult.Code.SEND_FAILED,
                    "Telegram API returned " + resp.statusCode(), resp.statusCode() >= 500, resp.statusCode(), (int) duration);
        } catch (Exception e) {
            return DeliveryResult.failure(DeliveryResult.Code.NETWORK_ERROR,
                    e.getMessage(), true, null, (int) (System.currentTimeMillis() - start));
        }
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        return deliver(context, new NotificationMessage(
                "telegram",
                "Vantis test notification — if you see this, Telegram is connected.",
                null));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
