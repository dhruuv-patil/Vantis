package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.NotificationLevel;
import com.flowops.notification.NotificationService;
import com.flowops.repository.AnomalyRepository;
import java.time.Duration;
import java.time.Instant;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists detector findings with every false-positive control: confidence gating,
 * severity banding (with the explainable score recorded in evidence), and
 * {@code dedup_key} dedup/cooldown so a repeating problem aggregates into one anomaly
 * card instead of spamming the dashboard.
 *
 * <p>Cooldown semantics ({@code ReliabilityProperties.cooldown}) — for the same
 * {@code (workflow, dedup_key)}:
 * <ul>
 *   <li>no record, or the record is {@code RESOLVED}/{@code FALSE_POSITIVE} — a new
 *       anomaly is opened (a human decision is never silently reopened);
 *   <li>last activity within the cooldown window and still {@code OPEN}/{@code
 *       ACKNOWLEDGED} — the finding is <em>aggregated</em> (affected_executions bumped,
 *       evidence freshened, severity only ever escalated);
 *   <li>last activity older than the cooldown and still {@code OPEN}/{@code
 *       ACKNOWLEDGED} — the quiet gap means a new episode: the same record is reopened
 *       with a fresh detection timestamp (no duplicate card, bounded card count).
 * </ul>
 *
 * <p>Everything persisted is secret-free: the evidence map carries only sizes, structure,
 * aggregates, and the severity rationale — never payload values.
 *
 * <p>When a new anomaly is opened (not aggregated), an in-app notification is published
 * to the workflow's creator (or org admins/owners for webhook runs) so the anomaly is
 * immediately visible in the top-bar notification inbox and linked to the anomaly detail.
 */
@Service
public class AnomalyRecorder {

    private static final Logger log = LoggerFactory.getLogger(AnomalyRecorder.class);

    private final AnomalyRepository anomalies;
    private final SeverityCalculator severityCalculator;
    private final ReliabilityProperties properties;
    private final NotificationService notifications;
    private final com.flowops.reliability.SlackAlertService slackAlerts;
    private final WebhookEventDispatcher webhookEvents;
    private final com.flowops.reliability.IncidentService incidentService;

    public AnomalyRecorder(
            AnomalyRepository anomalies,
            SeverityCalculator severityCalculator,
            ReliabilityProperties properties,
            NotificationService notifications,
            com.flowops.reliability.SlackAlertService slackAlerts,
            WebhookEventDispatcher webhookEvents,
            com.flowops.reliability.IncidentService incidentService) {
        this.anomalies = anomalies;
        this.severityCalculator = severityCalculator;
        this.properties = properties;
        this.notifications = notifications;
        this.slackAlerts = slackAlerts;
        this.webhookEvents = webhookEvents;
        this.incidentService = incidentService;
    }

    public AnomalyRecorder(
            AnomalyRepository anomalies,
            SeverityCalculator severityCalculator,
            ReliabilityProperties properties,
            NotificationService notifications,
            com.flowops.reliability.SlackAlertService slackAlerts,
            WebhookEventDispatcher webhookEvents) {
        this(anomalies, severityCalculator, properties, notifications, slackAlerts, webhookEvents, null);
    }

    /** Records one execution's (or one sweep's) findings. Never throws into the caller. */
    @Transactional
    public void record(UUID organizationId, UUID workflowId, UUID executionId,
            List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        for (Finding f : findings) {
            try {
                recordOne(organizationId, workflowId, executionId, f);
            } catch (RuntimeException e) {
                // Detection must never take down the run or the sweep.
                log.warn("Could not record anomaly ({}) for workflow {}: {}",
                        f.type(), workflowId, e.toString());
            }
        }
    }

    private void recordOne(UUID organizationId, UUID workflowId, UUID executionId, Finding f) {
        if (f.confidence() < properties.minConfidence()) {
            log.debug("Suppressed low-confidence finding {} (confidence {}) for workflow {}",
                    f.dedupKey(), f.confidence(), workflowId);
            return;
        }
        String dedupKey = DetectorSupport.capKey(f.dedupKey());
        java.util.Optional<Anomaly> existingOpt =
                anomalies.findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(workflowId, dedupKey);

        Anomaly createdAnomaly = null;
        boolean isNewAnomaly = false;

        if (existingOpt.isEmpty()) {
            Anomaly a = Anomaly.open(
                    organizationId, workflowId, f.nodeId(), executionId, f.type(),
                    severity(f, 1), f.metric(), f.expectedValue(), f.actualValue(),
                    f.deviation(), f.confidence(), withSeverity(f, 1), dedupKey);
            anomalies.save(a);
            createdAnomaly = a;
            isNewAnomaly = true;
        } else {
            Anomaly existing = existingOpt.get();
            // Idempotent re-evaluation: if this exact execution was already recorded on the anomaly, skip duplicate processing
            if (executionId != null && executionId.equals(existing.getExecutionId())) {
                log.debug("Execution {} already recorded for anomaly dedupKey {}, skipping duplicate aggregation",
                        executionId, dedupKey);
                return;
            }

            AnomalyStatus status = existing.getStatus();
            if (status == AnomalyStatus.RESOLVED || status == AnomalyStatus.FALSE_POSITIVE) {
                // A human closed this; a recurrence is a brand-new anomaly, not a re-open.
                Anomaly a = Anomaly.open(
                        organizationId, workflowId, f.nodeId(), executionId, f.type(),
                        severity(f, 1), f.metric(), f.expectedValue(), f.actualValue(),
                        f.deviation(), f.confidence(), withSeverity(f, 1), dedupKey);
                anomalies.save(a);
                createdAnomaly = a;
                isNewAnomaly = true;
            } else {
                Instant lastActivity = existing.getUpdatedAt() == null
                        ? existing.getDetectedAt() : existing.getUpdatedAt();
                boolean withinCooldown = Duration.between(lastActivity, Instant.now())
                        .compareTo(properties.cooldown()) < 0;
                if (withinCooldown) {
                    AnomalySeverity severity = severity(f, existing.getAffectedExecutions() + 1);
                    existing.aggregate(severity, f.actualValue(), f.deviation(), f.confidence(),
                            executionId, withSeverity(f, existing.getAffectedExecutions() + 1));
                    anomalies.save(existing);
                } else {
                    existing.newEpisode(severity(f, 1), f.actualValue(), f.deviation(),
                            f.confidence(), executionId, withSeverity(f, 1));
                    anomalies.save(existing);
                }
            }
        }

        Anomaly targetAnomaly = createdAnomaly != null ? createdAnomaly : existingOpt.orElse(null);
        if (targetAnomaly != null && incidentService != null) {
            recordIncidentIfEligible(organizationId, workflowId, executionId, f, targetAnomaly);
        }

        // Publish in-app notification when a NEW anomaly is opened (not aggregated)
        if (isNewAnomaly && createdAnomaly != null) {
            publishAnomalyNotification(organizationId, workflowId, executionId, f, createdAnomaly);
            // Also dispatch to outbound webhooks subscribed to anomaly.detected
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("anomalyId", createdAnomaly.getId().toString());
            payload.put("workflowId", workflowId.toString());
            payload.put("executionId", executionId.toString());
            payload.put("type", f.type().name());
            payload.put("severity", createdAnomaly.getSeverity().name());
            webhookEvents.dispatch(organizationId,
                    WebhookEventDispatcher.Event.ANOMALY_DETECTED, payload);
        }
    }

    private void recordIncidentIfEligible(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            Finding f,
            Anomaly anomaly) {
        if (anomaly.getSeverity() == AnomalySeverity.LOW) {
            return;
        }
        try {
            String nodeLabel = f.nodeId() != null && !f.nodeId().isBlank() ? f.nodeId() : "workflow";
            String title = "[" + f.type().name() + "] " + f.metric() + " in " + nodeLabel;
            String description = "Expected: " + f.expectedValue() + ". Actual: " + f.actualValue()
                    + " (deviation " + DetectorSupport.round(f.deviation(), 2) + "σ, confidence "
                    + DetectorSupport.round(f.confidence(), 2) + ")";

            incidentService.recordAnomalyIncident(
                    organizationId,
                    workflowId,
                    executionId,
                    anomaly.getId(),
                    workflowId + ":" + DetectorSupport.capKey(f.dedupKey()),
                    title,
                    description,
                    anomaly.getSeverity(),
                    anomaly.getEvidence()
            );
        } catch (RuntimeException e) {
            log.warn("Could not record incident for anomaly {}: {}", anomaly.getId(), e.toString());
        }
    }

    /**
     * Publishes an in-app notification and sends a Slack alert for a newly opened anomaly.
     * Handles both additively — neither must ever break anomaly recording.
     */
    private void publishAnomalyNotification(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            Finding f,
            Anomaly anomaly) {
        // 1. Publish in-app notification (server-side, scoped to the org's inbox)
        try {
            UUID createdBy = null; // webhook runs: NotificationService publishes to owners/admins
            String severityLabel = f.type().name() + " anomaly";
            String nodeLabel = f.nodeId() != null ? f.nodeId() : "workflow";
            String title = severityLabel + " detected in " + nodeLabel;
            String body = f.expectedValue() + " → " + f.actualValue()
                    + " (deviation " + DetectorSupport.round(f.deviation(), 2) + "σ, confidence "
                    + DetectorSupport.round(f.confidence(), 2) + ")";
            String link = "/reliability/" + anomaly.getId();

            NotificationLevel level = switch (anomaly.getSeverity()) {
                case CRITICAL, HIGH -> NotificationLevel.ERROR;
                case MEDIUM -> NotificationLevel.WARN;
                case LOW -> NotificationLevel.INFO;
            };

            notifications.publish(organizationId, createdBy, level, title, body, link);
        } catch (RuntimeException e) {
            log.warn("Could not publish anomaly notification for workflow {}: {}", workflowId, e.toString());
        }

        // 2. Send Slack alert if the org has a connected Slack integration
        try {
            slackAlerts.sendAnomalyAlert(organizationId, anomaly);
        } catch (RuntimeException e) {
            // Graceful: missing integration is handled inside sendAnomalyAlert, but
            // any unexpected failure here must not propagate.
            log.warn("Could not send Slack anomaly alert for workflow {}: {}", workflowId, e.toString());
        }
    }

    private AnomalySeverity severity(Finding f, int affectedExecutions) {
        return severityCalculator.severity(f.deviation(), f.confidence(), affectedExecutions);
    }

    /** Appends the explainable severity rationale to the finding's evidence. */
    private Map<String, Object> withSeverity(Finding f, int affectedExecutions) {
        double score = severityCalculator.score(f.deviation(), f.confidence(), affectedExecutions);
        Map<String, Object> evidence = new LinkedHashMap<>(f.evidence());
        evidence.put("severityScore", DetectorSupport.round(score, 2));
        evidence.put("severityRationale",
                "score = |deviation| " + DetectorSupport.round(f.deviation(), 2)
                        + " × confidence " + f.confidence()
                        + " × impact " + impactLabel(affectedExecutions));
        return evidence;
    }

    private String impactLabel(int affectedExecutions) {
        if (affectedExecutions >= 10) {
            return "widespread(" + affectedExecutions + " affected)";
        }
        if (affectedExecutions >= 3) {
            return "recurring(" + affectedExecutions + " affected)";
        }
        return "single(1 affected)";
    }
}
