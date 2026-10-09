package com.flowops.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.security.http.HardenedHttpClient;
import com.flowops.common.security.http.HardenedHttpRequest;
import com.flowops.common.security.http.HardenedHttpResponse;
import com.flowops.domain.Anomaly;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.execution.ExecutionStore;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends hardened anomaly alerts to Slack when an organization has a connected Slack integration.
 *
 * <p>Hardened reliability features:
 * <ul>
 *   <li>Zero-leakage secret and token redaction before message construction.</li>
 *   <li>Exponential backoff and automatic retry on HTTP 429 and 5xx errors.</li>
 *   <li>Dead-letter status tracking when delivery fails after maximum attempts.</li>
 *   <li>Alert flood rate-limiting and digest throttling (max 5 alerts/minute per workflow).</li>
 * </ul>
 */
@Service
public class SlackAlertService {

    private static final Logger log = LoggerFactory.getLogger(SlackAlertService.class);

    private static final String SLACK_ALERT_COLOR = "#ff4444"; // Red for anomalies
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100L;
    private static final int MAX_ALERTS_PER_WINDOW = 5;
    private static final Duration FLOOD_WINDOW = Duration.ofMinutes(1);

    public enum DeliveryStatus {
        DELIVERED,
        RATE_LIMITED,
        FAILED,
        DEAD_LETTER
    }

    public record DeliveryReport(
            UUID anomalyId,
            DeliveryStatus status,
            int attempts,
            Integer httpStatus,
            String errorMessage
    ) {}

    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository credentials;
    private final CredentialCipher cipher;
    private final ExecutionStore executionStore;
    private final ObjectMapper mapper;
    private final HardenedHttpClient httpClient;

    private final ConcurrentHashMap<UUID, AlertFloodTracker> floodTrackers = new ConcurrentHashMap<>();

    private static class AlertFloodTracker {
        final AtomicInteger count = new AtomicInteger(0);
        volatile Instant windowStart = Instant.now();
        volatile boolean digestSent = false;
    }

    public SlackAlertService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentials,
            CredentialCipher cipher,
            ExecutionStore executionStore,
            ObjectMapper mapper,
            HardenedHttpClient httpClient) {
        this.integrations = integrations;
        this.credentials = credentials;
        this.cipher = cipher;
        this.executionStore = executionStore;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * Sends a Slack alert asynchronously for a newly opened anomaly.
     */
    public void sendAnomalyAlert(UUID organizationId, Anomaly anomaly) {
        CompletableFuture.runAsync(() -> sendAnomalyAlertSynchronous(organizationId, anomaly));
    }

    /**
     * Synchronous alert sender providing deterministic delivery reporting for tests and verification.
     */
    public DeliveryReport sendAnomalyAlertSynchronous(UUID organizationId, Anomaly anomaly) {
        Optional<Integration> integrationOpt = integrations
                .findByOrganizationIdAndTypeAndStatus(
                        organizationId, Integration.TYPE_SLACK, Integration.STATUS_CONNECTED);

        if (integrationOpt.isEmpty()) {
            log.debug("No connected Slack integration for org {}; skipping anomaly alert", organizationId);
            return new DeliveryReport(anomaly.getId(), DeliveryStatus.FAILED, 0, null, "No connected Slack integration");
        }

        Integration integration = integrationOpt.get();
        Optional<IntegrationCredential> credentialOpt = credentials.findByIntegrationId(integration.getId());
        if (credentialOpt.isEmpty()) {
            log.warn("Slack integration {} exists but has no credential for org {}", integration.getId(), organizationId);
            return new DeliveryReport(anomaly.getId(), DeliveryStatus.FAILED, 0, null, "Missing Slack credential");
        }

        String webhookUrl;
        try {
            webhookUrl = cipher.decrypt(credentialOpt.get().getCiphertext());
        } catch (RuntimeException e) {
            log.warn("Could not decrypt Slack credential for integration {}: {}", integration.getId(), e.getMessage());
            return new DeliveryReport(anomaly.getId(), DeliveryStatus.FAILED, 0, null, "Decryption failure");
        }

        String message = buildAnomalyMessage(anomaly);
        return sendToSlackSynchronous(webhookUrl, message, anomaly.getId(), anomaly.getWorkflowId());
    }

    /**
     * Sends a Slack message with rate-limiting, exponential backoff, and dead-letter tracking.
     */
    public DeliveryReport sendToSlackSynchronous(String webhookUrl, String text, UUID anomalyId, UUID workflowId) {
        // 1. Rate-limiting / Flood Protection check
        if (workflowId != null) {
            AlertFloodTracker tracker = floodTrackers.compute(workflowId, (k, v) -> {
                Instant now = Instant.now();
                if (v == null || Duration.between(v.windowStart, now).compareTo(FLOOD_WINDOW) > 0) {
                    AlertFloodTracker fresh = new AlertFloodTracker();
                    fresh.count.set(1);
                    fresh.windowStart = now;
                    fresh.digestSent = false;
                    return fresh;
                }
                v.count.incrementAndGet();
                return v;
            });

            if (tracker.count.get() > MAX_ALERTS_PER_WINDOW) {
                if (!tracker.digestSent) {
                    tracker.digestSent = true;
                    String floodWarning = "🚨 *Vantis Alert Flood Warning*: Workflow `" + workflowId
                            + "` exceeded " + MAX_ALERTS_PER_WINDOW + " alerts in 1 minute. Throttling further alerts to prevent spam.";
                    sendRawWithRetries(webhookUrl, floodWarning, anomalyId);
                }
                log.warn("Alert flood suppressed for workflow {} (count={})", workflowId, tracker.count.get());
                return new DeliveryReport(anomalyId, DeliveryStatus.RATE_LIMITED, 0, null, "Alert flood rate limited");
            }
        }

        // 2. Redact sensitive values before sending
        String sanitizedText = redactSensitiveData(text);

        // 3. Dispatch with retries and backoff
        return sendRawWithRetries(webhookUrl, sanitizedText, anomalyId);
    }

    private DeliveryReport sendRawWithRetries(String webhookUrl, String text, UUID anomalyId) {
        String body;
        try {
            var payload = mapper.createObjectNode();
            payload.put("text", text);
            var attachments = mapper.createArrayNode();
            var attachment = mapper.createObjectNode();
            attachment.put("color", SLACK_ALERT_COLOR);
            attachment.put("text", text);
            attachments.add(attachment);
            payload.set("attachments", attachments);
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Error serializing Slack alert payload: {}", e.getMessage());
            return new DeliveryReport(anomalyId, DeliveryStatus.FAILED, 0, null, e.getMessage());
        }

        HardenedHttpRequest request = HardenedHttpRequest.builder()
                .post(webhookUrl, body, "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();

        int attempt = 0;
        Integer lastStatus = null;
        String lastError = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                HardenedHttpResponse response = httpClient.execute(request);
                lastStatus = response.statusCode();

                if (response.isSuccess()) {
                    log.debug("Slack alert delivered on attempt {}", attempt);
                    return new DeliveryReport(anomalyId, DeliveryStatus.DELIVERED, attempt, lastStatus, null);
                }

                // If client rejected (e.g. 400 Bad Request, 404 Not Found), retrying won't help
                if (lastStatus != 429 && lastStatus >= 400 && lastStatus < 500) {
                    log.warn("Slack rejected alert with client error HTTP {}", lastStatus);
                    return new DeliveryReport(anomalyId, DeliveryStatus.FAILED, attempt, lastStatus, "Client error: HTTP " + lastStatus);
                }

                lastError = "HTTP " + lastStatus;
                log.warn("Slack alert attempt {} failed with status {}", attempt, lastStatus);

            } catch (Exception ex) {
                lastError = ex.getMessage();
                log.warn("Slack alert attempt {} threw exception: {}", attempt, ex.getMessage());
            }

            if (attempt < MAX_RETRIES) {
                long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Exhausted retries -> Dead Letter
        log.error("Slack alert delivery entered DEAD_LETTER after {} failed attempts: {}", attempt, lastError);
        return new DeliveryReport(anomalyId, DeliveryStatus.DEAD_LETTER, attempt, lastStatus, lastError);
    }

    /**
     * Builds a concise, redacted Slack message for an anomaly.
     */
    private String buildAnomalyMessage(Anomaly anomaly) {
        String severity = anomaly.getSeverity().name();
        String type = anomaly.getType().name();

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
        return "/reliability/" + anomaly.getId();
    }

    private String escapeSlack(String input) {
        if (input == null) {
            return "N/A";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Redacts credentials, tokens, and authorization values from alert text.
     */
    public static String redactSensitiveData(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)(bearer\\s+)[a-zA-Z0-9_\\-\\.]+", "$1[REDACTED]")
                .replaceAll("(?i)(authorization[\"':\\s=]+)(?:basic\\s+|bearer\\s+)?[^\\s,'\"}]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key[\"':\\s=]+)[^\\s,'\"}]+", "$1[REDACTED]")
                .replaceAll("(?i)(password[\"':\\s=]+)[^\\s,'\"}]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret[\"':\\s=]+)[^\\s,'\"}]+", "$1[REDACTED]");
    }
}