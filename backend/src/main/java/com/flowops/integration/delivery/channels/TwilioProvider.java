package com.flowops.integration.delivery.channels;

import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Twilio SMS notification provider.
 */
@Component
public class TwilioProvider implements NotificationProvider {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public IntegrationType type() {
        return IntegrationType.TWILIO;
    }

    @Override
    public String displayName() {
        return "Twilio SMS";
    }

    @Override
    public String description() {
        return "Send SMS & WhatsApp notifications via Twilio Programmable Messaging API.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("accountSid", "Account SID", true,
                        "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "AC12345",
                        "Found on Twilio console dashboard."),
                new CredentialField("authToken", "Auth Token", true,
                        "your_auth_token", "your_auth_token",
                        "Secret auth token from Twilio console."),
                new CredentialField("fromNumber", "From Phone Number", false,
                        "+15551234567", "+15551234567",
                        "Your Twilio phone number in E.164 format.")
        );
    }

    @Override
    public String secretKey() {
        return "authToken";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        String sid = context.credentials().get("accountSid");
        String token = context.credentials().get("authToken");
        if (sid == null || sid.isBlank() || token == null || token.isBlank()) {
            return ConnectionTestResult.failure("Account SID and Auth Token are required");
        }
        try {
            URI uri = URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid.trim() + ".json");
            String basic = Base64.getEncoder().encodeToString(
                    (sid.trim() + ":" + token.trim()).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Basic " + basic)
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ConnectionTestResult.success("Twilio credentials verified successfully");
            }
            return ConnectionTestResult.failure("Twilio auth failed (HTTP " + resp.statusCode() + ")");
        } catch (Exception e) {
            return ConnectionTestResult.failure("Twilio test error: " + e.getMessage());
        }
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        long start = System.currentTimeMillis();
        String sid = context.credentials().get("accountSid");
        String token = context.credentials().get("authToken");
        String from = context.credentials().get("fromNumber");

        if (sid == null || sid.isBlank() || token == null || token.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG, "Twilio SID/token missing", false, null, 0);
        }

        Object toObj = message.payload() != null ? message.payload().get("to") : null;
        String to = toObj != null ? toObj.toString() : null;
        if (to == null || to.isBlank()) {
            return DeliveryResult.failure(DeliveryResult.Code.INVALID_CONFIG,
                    "No destination phone number. Set 'to' in payload.", false, null, 0);
        }

        try {
            URI uri = URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid.trim() + "/Messages.json");
            String basic = Base64.getEncoder().encodeToString(
                    (sid.trim() + ":" + token.trim()).getBytes(StandardCharsets.UTF_8));
            String form = "From=" + URLEncoder.encode(from != null ? from : "", StandardCharsets.UTF_8)
                    + "&To=" + URLEncoder.encode(to, StandardCharsets.UTF_8)
                    + "&Body=" + URLEncoder.encode(
                    message.text() != null ? message.text() : "Vantis notification", StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return DeliveryResult.success(resp.statusCode(), (int) duration);
            }
            return DeliveryResult.failure(DeliveryResult.Code.SEND_FAILED,
                    "Twilio API error " + resp.statusCode(), resp.statusCode() >= 500, resp.statusCode(), (int) duration);
        } catch (Exception e) {
            return DeliveryResult.failure(DeliveryResult.Code.NETWORK_ERROR,
                    e.getMessage(), true, null, (int) (System.currentTimeMillis() - start));
        }
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        return deliver(context, new NotificationMessage("twilio",
                "Vantis test SMS — Twilio is connected.", null));
    }
}
