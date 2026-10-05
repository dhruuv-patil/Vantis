package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/** Discord: posts a message via the connected Discord integration. */
@Component
public class DiscordExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "discord";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String message = ctx.configString("message");
        String username = ctx.configString("username");
        if (message == null || message.isBlank()) {
            return NodeResult.fail("Discord requires a 'message'.");
        }

        String webhookUrl = ctx.secret("webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("delivered", false);
            output.put("reason", "No connected Discord integration — configure an integration to deliver.");
            output.put("message", ctx.interpolate(message));
            ctx.log().warn("Discord not sent: no webhook URL.");
            return NodeResult.success(output);
        }

        String interpolated = ctx.interpolate(message);
        String interpolatedUsername = username != null ? ctx.interpolate(username) : "Vantis";
        java.net.URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(webhookUrl.trim());
        } catch (IllegalArgumentException | com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail("Discord webhook target rejected: " + blocked.getMessage());
        }

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String payload = "{\"content\":\"" + interpolated.replace("\"", "\\\"") + "\",\"username\":\"" + interpolatedUsername.replace("\"", "\\\"") + "\"}";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("delivered", response.statusCode() >= 200 && response.statusCode() < 300);
            output.put("status", response.statusCode());
            output.put("message", interpolated);
            ctx.log().info("Discord delivered: " + response.statusCode());
            return NodeResult.success(output);
        } catch (Exception e) {
            return NodeResult.fail("Discord error: " + e.getMessage());
        }
    }
}