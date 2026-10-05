package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/** Microsoft Teams: posts a message via the connected Teams integration. */
@Component
public class TeamsExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "teams";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String message = ctx.configString("message");
        String title = ctx.configString("title");
        if (message == null || message.isBlank()) {
            return NodeResult.fail("Teams requires a 'message'.");
        }

        String webhookUrl = ctx.secret("webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("delivered", false);
            output.put("reason", "No connected Teams integration — configure an integration to deliver.");
            output.put("message", ctx.interpolate(message));
            ctx.log().warn("Teams not sent: no webhook URL.");
            return NodeResult.success(output);
        }

        String interpolated = ctx.interpolate(message);
        String interpolatedTitle = title != null ? ctx.interpolate(title) : "Vantis Notification";
        try {
            java.net.URI uri = java.net.URI.create(webhookUrl);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String payload = "{\"title\":\"" + interpolatedTitle.replace("\"", "\\\"") + "\",\"text\":\"" + interpolated.replace("\"", "\\\"") + "\"}";
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
            ctx.log().info("Teams delivered: " + response.statusCode());
            return NodeResult.success(output);
        } catch (Exception e) {
            return NodeResult.fail("Teams error: " + e.getMessage());
        }
    }
}