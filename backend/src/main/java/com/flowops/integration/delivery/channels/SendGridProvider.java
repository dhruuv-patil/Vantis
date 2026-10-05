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
 * SendGrid email delivery provider.
 */
@Component
public class SendGridProvider implements NotificationProvider {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public IntegrationType type() {
        return IntegrationType.SENDGRID;
    }

    @Override
    public String displayName() {
        return "SendGrid";
    }

    @Override
    public String description() {
        return "Send transactional email via SendGrid Web API v3.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("apiKey", "API Key", true,
                        "SG.xxxxxxxx...", "SG.12345",
                        "SendGrid API Key with Mail Send permissions."),
                new CredentialField("fromEmail", "From Email", false,
                        "notifications@yourdomain.com", "notifications@yourdomain.com",
                        "Verified sender email in SendGrid.")
        );
    }

    @Override
    public String secretKey() {
        return "apiKey";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String apiKey = context.credentials().get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            return ConnectionTestResult.failure("API key is required");
        }
        try {
            URI uri = URI.create("https://api.sendgrid.com/v3/scopes");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ConnectionTestResult.success("SendGrid API key verified successfully");
            }
            return ConnectionTestResult.failure("SendGrid authentication failed (HTTP " + resp.statusCode() + ")");
        } catch (Exception e) {
            return ConnectionTestResult.failure("SendGrid test error: " + e.getMessage());
        }
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        long start = System.currentTimeMillis();
        String apiKey = context.credentials().get("apiKey");
        String from = context.credentials().get("fromEmail");

        if (apiKey == null || apiKey.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG, "SendGrid API key missing", false, null, 0);
        }

        // Recipient from payload
        Object toObj = message.payload() != null ? message.payload().get("to") : null;
        String to = toObj != null ? toObj.toString() : null;
        if (to == null || to.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "No recipient email. Set 'to' in payload.", false, null, 0);
        }

        try {
            URI uri = URI.create("https://api.sendgrid.com/v3/mail/send");
            String subject = message.payload() != null && message.payload().get("subject") != null
                    ? message.payload().get("subject").toString() : "Vantis Notification";
            String fromAddr = from != null && !from.isBlank() ? from : "no-reply@vantis.run";

            String jsonPayload = "{"
                    + "\"personalizations\":[{\"to\":[{\"email\":\"" + escape(to) + "\"}]}],"
                    + "\"from\":{\"email\":\"" + escape(fromAddr) + "\"},"
                    + "\"subject\":\"" + escape(subject) + "\","
                    + "\"content\":[{\"type\":\"text/plain\",\"value\":\"" + escape(message.text()) + "\"}]"
                    + "}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return DeliveryResult.success(resp.statusCode(), (int) duration);
            }
            return DeliveryResult.failure(DeliveryResult.Code.SEND_FAILED,
                    "SendGrid returned " + resp.statusCode(), resp.statusCode() >= 500, resp.statusCode(), (int) duration);
        } catch (Exception e) {
            return DeliveryResult.failure(DeliveryResult.Code.NETWORK_ERROR,
                    e.getMessage(), true, null, (int) (System.currentTimeMillis() - start));
        }
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        String from = context.credentials().get("fromEmail");
        return deliver(context, new NotificationMessage("sendgrid",
                "Vantis test email — SendGrid is connected.",
                from != null && !from.isBlank()
                        ? java.util.Map.of("to", from, "subject", "Vantis Test Email")
                        : null));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
