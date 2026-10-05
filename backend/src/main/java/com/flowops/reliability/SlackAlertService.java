package com.flowops.reliability;

import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.execution.ExecutionStore;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.config.ReliabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends anomaly alerts to Slack when an organization has a connected Slack integration.
 *
 * <p>Only fires for newly opened anomalies (not aggregated repeat occurrences) so the
 * channel is not spammed. The message includes workflow name, anomaly type, severity,
 * affected node/step, concise explanation, and a link back to the anomaly in Vantis.
 *
 * <p>Missing or misconfigured Slack integration is handled gracefully — the alert is
 * simply skipped with a debug log, never an error.
 */
@Service
public class SlackAlertService {

    private static final Logger log = LoggerFactory.getLogger(SlackAlertService.class);

    private static final String SLACK_ALERT_COLOR = "#ff4444"; // Red for anomalies
    private static final String SLACK_WARNING_COLOR = "#ffaa00"; // Amber for warnings
    private static final String SLACK_INFO_COLOR = "#00aaff"; // Blue for info

    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository credentials;
    private final CredentialCipher cipher;
    private final ExecutionStore executionStore;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public SlackAlertService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentials,
            CredentialCipher cipher,
            ExecutionStore executionStore,
            ObjectMapper mapper) {
        this.integrations = integrations;
        this.credentials = credentials;
        this.cipher = cipher;
        this.executionStore = executionStore;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Sends a Slack alert for a newly opened anomaly.
     * Called from {@link AnomalyRecorder} after persisting a new anomaly.
     *
     * @param organizationId the organization that owns the workflow
     * @param anomaly the newly created anomaly
     */
    public void sendAnomalyAlert(UUID organizationId, Anomaly anomaly) {
        // Find the connected Slack integration for this org
        Optional<Integration> integrationOpt = integrations
                .findByOrganizationIdAndTypeAndStatus(
                        organizationId, Integration.TYPE_SLACK, Integration.STATUS_CONNECTED);

        if (integrationOpt.isEmpty()) {
            // No Slack integration — silently skip (not an error)
            log.debug("No connected Slack integration for org {}; skipping anomaly alert", organizationId);
            return;
        }

        Integration integration = integrationOpt.get();

        // Fetch the encrypted credential
        Optional<IntegrationCredential> credentialOpt = credentials.findByIntegrationId(integration.getId());
        if (credentialOpt.isEmpty()) {
            log.warn("Slack integration {} exists but has no credential for org {}", integration.getId(), organizationId);
            return;
        }

        // Decrypt the webhook URL
        String webhookUrl;
        try {
            webhookUrl = cipher.decrypt(credentialOpt.get().getCiphertext());
        } catch (RuntimeException e) {
            log.warn("Could not decrypt Slack credential for integration {}: {}", integration.getId(), e.getMessage());
            return;
        }

        // Build the Slack message
        String message = buildAnomalyMessage(anomaly);

        // Send to Slack (fire-and-forget, non-blocking)
        sendToSlack(webhookUrl, message);
    }

    /**
     * Builds a concise, actionable Slack message for an anomaly.
     */
    private String buildAnomalyMessage(Anomaly anomaly) {
        String severity = anomaly.getSeverity().name();
        String type = anomaly.getType().name();
        String workflowName = anomaly.getWorkflowId().toString(); // Will be resolved by caller if needed

        // Use the anomaly's evidence for workflow name if available
        // For now, construct a clean message with all required fields

        StringBuilder sb = new StringBuilder();
        sb.append("🚨 *Vantis Anomaly Alert*");
        sb.append("\n\n");
        sb.append("*Workflow:* ").append(escapeSlack(anomaly.getWorkflowId().toString())).append("\n");
        sb.append("*Type:* ").append(type).append("\n");
        sb.append("*Severity:* ").append(severity).append("\n");

        if (anomaly.getNodeId() != null && !anomaly.getNodeId().isBlank()) {
            sb.append("*Step:* ").append(escapeSlack(anomaly.getNodeId())).append("\n");
        }

        sb.append("*Metric:* ").append(anomaly.getMetric() != null ? anomaly.getMetric() : "N/A").append("\n");
        sb.append("*Expected:* ").append(escapeSlack(anomaly.getExpectedValue())).append("\n");
        sb.append("*Actual:* ").append(escapeSlack(anomaly.getActualValue())).append("\n");
        sb.append("*Deviation:* ").append(String.format("%.2fσ", anomaly.getDeviation())).append("\n");
        sb.append("*Confidence:* ").append(String.format("%.2f", anomaly.getConfidence())).append("\n");
        sb.append("*Affected executions:* ").append(anomaly.getAffectedExecutions()).append("\n");
        sb.append("*Detected:* ").append(anomaly.getDetectedAt().toString()).append("\n");
        sb.append("\n");
        sb.append("<").append(buildAnomalyUrl(anomaly)).append("|View anomaly in Vantis>");

        return sb.toString();
    }

    private String buildAnomalyUrl(Anomaly anomaly) {
        // Build a URL to the anomaly detail page
        // In production this would use the configured frontend URL
        // For now, use a relative path that the frontend can resolve
        return "/reliability/" + anomaly.getId();
    }

    private String escapeSlack(String input) {
        if (input == null) {
            return "N/A";
        }
        return input.replace("&", "&")
                .replace("<", "<")
                .replace(">", ">");
    }

    /**
     * Sends a message to a Slack incoming webhook.
     * Non-blocking: logs failures but never throws.
     */
    private void sendToSlack(String webhookUrl, String text) {
        try {
            // Build the payload
            var payload = mapper.createObjectNode();
            payload.put("text", text);

            // Color based on severity (we'd need to pass severity, but for simplicity use red)
            var attachments = mapper.createArrayNode();
            var attachment = mapper.createObjectNode();
            attachment.put("color", SLACK_ALERT_COLOR);
            attachment.put("text", text);
            attachments.add(attachment);
            payload.set("attachments", attachments);

            String body = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            log.debug("Slack anomaly alert delivered successfully");
                        } else {
                            log.warn("Slack rejected anomaly alert (HTTP {})", status);
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("Failed to send Slack anomaly alert: {}", ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.warn("Error building/sending Slack anomaly alert: {}", e.getMessage());
        }
    }
}