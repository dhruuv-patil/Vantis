package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/** Slack: posts a message via the connected Slack integration. */
@Component
public class SlackExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "slack";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String message = ctx.configString("message");
        if (message == null || message.isBlank()) {
            return NodeResult.fail("Slack requires a 'message'.");
        }

        String webhookUrl = ctx.secret("webhookUrl");
        String channelName = ctx.secret("channelName");

        if (webhookUrl == null || webhookUrl.isBlank()) {
            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("delivered", false);
            output.put("reason", "No connected Slack integration — configure an integration to deliver.");
            output.put("message", ctx.interpolate(message));
            ctx.log().warn("Slack not sent: no webhook URL.");
            return NodeResult.success(output);
        }

        String interpolated = ctx.interpolate(message);
        java.net.URI uri;
        try {
            uri = com.flowops.common.security.SsrfGuard.validate(webhookUrl.trim());
        } catch (IllegalArgumentException | com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail("Slack webhook target rejected: " + blocked.getMessage());
        }

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String payload = "{\"text\":\"" + interpolated.replace("\"", "\\\"") + "\"}";
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
            if (channelName != null) output.put("channel", channelName);
            ctx.log().info("Slack delivered: " + response.statusCode());
            return NodeResult.success(output);
        } catch (Exception e) {
            return NodeResult.fail("Slack error: " + e.getMessage());
        }
    }
}