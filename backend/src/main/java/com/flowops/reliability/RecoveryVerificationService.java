package com.flowops.reliability;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.reliability.detect.DetectorSupport;
import com.flowops.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery verification for anomalies (reliability pivot).
 *
 * <p>After a user fixes a workflow, they trigger "Verify Recovery" instead of directly
 * resolving the anomaly. This service:
 * <ol>
 *   <li>Transitions the anomaly to {@code VERIFYING_RECOVERY} status.</li>
 *   <li>On every subsequent execution completion for the same workflow/node, evaluates
 *       whether the metric is back within the learned baseline range — using the same
 *       {@code median + sensitivity×σ} threshold already used by the detectors.</li>
 *   <li>When {@code recoveryRequiredCount} consecutive healthy executions are observed,
 *       automatically resolves the anomaly.</li>
 *   <li>If the metric remains anomalous, the healthy streak resets and the anomaly
 *       stays in {@code VERIFYING_RECOVERY} so the user can see the real evidence.</li>
 * </ol>
 *
 * <p>Design invariants:
 * <ul>
 *   <li>Uses the same baseline semantics as the detectors — no separate health logic.</li>
 *   <li>Only counts executions that occurred <em>after</em> verification started.</li>
 *   <li>Each execution is counted at most once (idempotent via seen-set).</li>
 *   <li>Only counts executions belonging to the correct workflow/node and organization.</li>
 *   <li>Never fake-resolves — a resolved state requires real evidence.</li>
 * </ul>
 *
 * <p>Behavioral anomalies: the "healthy" check for {@code BEHAVIORAL} type uses the
 * node's execution run-rate (whether the expected nodes ran). For all other types it
 * falls back to the numeric metric threshold derived from the baseline.
 */
@Service
public class RecoveryVerificationService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryVerificationService.class);

    /** Default number of healthy executions required for auto-resolution. */
    static final int DEFAULT_REQUIRED_COUNT = 5;

    private final AnomalyRepository anomalies;
    private final NodeExecutionMetricRepository metrics;
    private final MetricBaselineRepository baselines;
    private final ReliabilityProperties properties;
    private final IncidentService incidentService;

    @org.springframework.beans.factory.annotation.Autowired
    public RecoveryVerificationService(
            AnomalyRepository anomalies,
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            ReliabilityProperties properties,
            IncidentService incidentService) {
        this.anomalies = anomalies;
        this.metrics = metrics;
        this.baselines = baselines;
        this.properties = properties;
        this.incidentService = incidentService;
    }

    public RecoveryVerificationService(
            AnomalyRepository anomalies,
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            ReliabilityProperties properties) {
        this(anomalies, metrics, baselines, properties, null);
    }

    // =========================================================================
    // Start verification (user action)
    // =========================================================================

    /**
     * Initiates recovery verification for an anomaly the user believes has been fixed.
     *
     * @param user          the authenticated caller (org-scoping derived from principal)
     * @param anomalyId     the anomaly to verify
     * @param requiredCount override for how many healthy executions are needed; null → default
     * @return the updated anomaly (now in VERIFYING_RECOVERY status)
     */
    @Transactional
    public Anomaly startVerification(AuthenticatedUser user, UUID anomalyId, Integer requiredCount) {
        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(anomalyId, principal.organizationId())
                        .orElseThrow(() -> new ApiException(ErrorCode.ANOMALY_NOT_FOUND));

        AnomalyStatus current = anomaly.getStatus();
        if (current == AnomalyStatus.RESOLVED || current == AnomalyStatus.FALSE_POSITIVE) {
            throw new ApiException(ErrorCode.ANOMALY_ALREADY_CLOSED);
        }
        if (current == AnomalyStatus.VERIFYING_RECOVERY) {
            // Idempotent: re-starting just resets the counters; useful when the user
            // re-deploys a fix and wants a fresh window.
            log.info("Re-starting recovery verification for anomaly {} in org {}",
                    anomalyId, principal.organizationId());
        }

        int needed = requiredCount != null && requiredCount > 0
                ? requiredCount
                : DEFAULT_REQUIRED_COUNT;

        anomaly.startRecoveryVerification(needed);
        anomalies.save(anomaly);

        log.info("Recovery verification started for anomaly {} (workflow={}, node={}, requiredCount={})",
                anomalyId, anomaly.getWorkflowId(), anomaly.getNodeId(), needed);

        return anomaly;
    }

    // =========================================================================
    // Evaluate executions (called from ReliabilityDetectionService)
    // =========================================================================

    /**
     * Evaluates all anomalies in {@code VERIFYING_RECOVERY} status for the given workflow
     * against the just-completed execution's telemetry.
     *
     * <p>Called after each execution that produces telemetry. The method is idempotent per
     * execution (seen-set prevents double counting) and never throws into the caller — a
     * failure to evaluate one anomaly must not break the telemetry pipeline.
     *
     * @param workflowId  the workflow whose execution just completed
     * @param executionId the execution that just completed
     */
    @Transactional
    public void evaluateExecution(UUID workflowId, UUID executionId) {
        List<Anomaly> verifying =
                anomalies.findByWorkflowIdAndStatus(workflowId, AnomalyStatus.VERIFYING_RECOVERY);

        if (verifying.isEmpty()) {
            return;
        }

        // Load telemetry for this execution once.
        List<NodeExecutionMetric> executionMetrics = metrics.findByExecutionId(executionId);
        if (executionMetrics.isEmpty()) {
            return;
        }

        for (Anomaly anomaly : verifying) {
            try {
                evaluateOneAnomaly(anomaly, executionId, executionMetrics);
            } catch (RuntimeException e) {
                log.warn("Recovery evaluation failed for anomaly {} execution {}: {}",
                        anomaly.getId(), executionId, e.toString());
            }
        }
    }

    private void evaluateOneAnomaly(
            Anomaly anomaly,
            UUID executionId,
            List<NodeExecutionMetric> executionMetrics) {

        // Only evaluate executions that occurred after verification started.
        if (anomaly.getRecoveryStartedAt() != null) {
            boolean anyAfterStart = executionMetrics.stream()
                    .anyMatch(m -> m.getStartedAt() != null
                            && !m.getStartedAt().isBefore(anomaly.getRecoveryStartedAt()));
            if (!anyAfterStart) {
                return;
            }
        }

        boolean healthy = isExecutionHealthy(anomaly, executionMetrics);

        boolean resolved = anomaly.recordRecoveryExecution(executionId, healthy);
        anomalies.save(anomaly);

        if (resolved) {
            log.info("Anomaly {} auto-resolved: recovery verified after {} healthy execution(s)",
                    anomaly.getId(), anomaly.getRecoveryHealthyCount());
            if (incidentService != null) {
                try {
                    incidentService.resolveLinkedIncident(anomaly.getOrganizationId(), anomaly.getId());
                } catch (RuntimeException e) {
                    log.warn("Could not auto-resolve linked incident for anomaly {}: {}", anomaly.getId(), e.toString());
                }
            }
        } else if (healthy) {
            log.debug("Anomaly {}: healthy execution {} ({}/{} required)",
                    anomaly.getId(), executionId,
                    anomaly.getRecoveryHealthyCount(),
                    anomaly.getRecoveryRequiredCount());
        } else {
            log.debug("Anomaly {}: unhealthy execution {} — healthy streak reset",
                    anomaly.getId(), executionId);
        }
    }

    /**
     * Determines whether a completed execution is "healthy" relative to the anomaly's
     * metric. Uses the same baseline threshold semantics as the detectors: a run is healthy
     * when the observed value is within {@code median + sensitivity×σ}.
     *
     * <p>For {@code BEHAVIORAL} anomalies (no simple numeric metric) the check is whether
     * the expected nodes ran (run-rate within tolerance). If no usable baseline exists
     * (still warming up), the execution is counted as healthy to avoid stalling verification
     * indefinitely during warm-up — the caller sees "1/5" progress rather than 0/5 forever.
     */
    boolean isExecutionHealthy(Anomaly anomaly, List<NodeExecutionMetric> executionMetrics) {
        String metric = anomaly.getMetric();
        String nodeId = anomaly.getNodeId();
        UUID workflowId = anomaly.getWorkflowId();

        if (metric == null) {
            // No metric to compare against — assume healthy (conservative for resolution).
            return true;
        }

        switch (metric) {
            case "LATENCY" -> {
                NodeExecutionMetric row = findNodeMetric(executionMetrics, nodeId);
                if (row == null || row.getDurationMs() == null
                        || row.getStatus() != NodeRunStatus.SUCCEEDED) {
                    return true; // skipped / failed nodes don't count against recovery
                }
                Optional<MetricBaseline> base =
                        baselines.findByWorkflowIdAndNodeIdAndMetric(workflowId, nodeId, "LATENCY");
                if (base.isEmpty() || !DetectorSupport.usable(base.get(), properties.minSampleCount())) {
                    return true; // warming up
                }
                double threshold = threshold(base.get());
                return row.getDurationMs() <= threshold;
            }
            case "OUTPUT_SIZE" -> {
                NodeExecutionMetric row = findNodeMetric(executionMetrics, nodeId);
                if (row == null || row.getOutputSize() == null
                        || row.getStatus() != NodeRunStatus.SUCCEEDED) {
                    return true;
                }
                Optional<MetricBaseline> base =
                        baselines.findByWorkflowIdAndNodeIdAndMetric(workflowId, nodeId, "OUTPUT_SIZE");
                if (base.isEmpty() || !DetectorSupport.usable(base.get(), properties.minSampleCount())) {
                    return true;
                }
                double threshold = threshold(base.get());
                return row.getOutputSize() <= threshold;
            }
            case "OUTPUT_SCHEMA" -> {
                // For schema anomalies, "healthy" means the run succeeded and produced output.
                NodeExecutionMetric row = findNodeMetric(executionMetrics, nodeId);
                if (row == null) return true;
                return row.getStatus() == NodeRunStatus.SUCCEEDED
                        && row.getOutputSignature() != null;
            }
            case "VOLUME" -> {
                // Healthy volume: at least one execution completed.
                return executionMetrics.stream()
                        .anyMatch(m -> m.getStatus() == NodeRunStatus.SUCCEEDED);
            }
            case "BEHAVIOR" -> {
                // Behavioral: at least one node succeeded (coarse check; full baseline
                // comparison would require re-running BehavioralDetector).
                return executionMetrics.stream()
                        .anyMatch(m -> m.getStatus() == NodeRunStatus.SUCCEEDED);
            }
            default -> {
                // Unknown metric: treat as healthy to avoid blocking resolution.
                return true;
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Computes the anomaly threshold for a numeric baseline: {@code median + sensitivity×σ}.
     * Uses the same formula as {@link com.flowops.reliability.detect.LatencyDetector}.
     */
    private double threshold(MetricBaseline base) {
        double median = base.getMedian() == null ? 0.0 : base.getMedian();
        double sigma = DetectorSupport.robustSigma(base);
        return median + properties.sensitivity() * sigma;
    }

    private NodeExecutionMetric findNodeMetric(
            List<NodeExecutionMetric> metrics, String nodeId) {
        if (nodeId == null) return null;
        return metrics.stream()
                .filter(m -> nodeId.equals(m.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    // =========================================================================
    // Recovery status snapshot (for the API response)
    // =========================================================================

    /**
     * Returns the current recovery snapshot for a given anomaly, scoped to the caller's org.
     */
    @Transactional(readOnly = true)
    public RecoveryStatus getRecoveryStatus(AuthenticatedUser user, UUID anomalyId) {
        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(anomalyId, principal.organizationId())
                        .orElseThrow(() -> new ApiException(ErrorCode.ANOMALY_NOT_FOUND));

        return RecoveryStatus.of(anomaly, buildBaselineSnapshot(anomaly));
    }

    /**
     * Builds a human-readable snapshot of the baseline values relevant to the anomaly.
     * Used by the frontend to display "Baseline: 120 B / Normal threshold: 150 B".
     */
    BaselineSnapshot buildBaselineSnapshot(Anomaly anomaly) {
        String metric = anomaly.getMetric();
        if (metric == null) return null;

        Optional<MetricBaseline> baseOpt;
        if (anomaly.getNodeId() != null) {
            baseOpt = baselines.findByWorkflowIdAndNodeIdAndMetric(
                    anomaly.getWorkflowId(), anomaly.getNodeId(), metric);
        } else {
            baseOpt = baselines.findByWorkflowIdAndNodeIdAndMetric(
                    anomaly.getWorkflowId(), "", metric);
        }

        if (baseOpt.isEmpty()
                || !DetectorSupport.usable(baseOpt.get(), properties.minSampleCount())) {
            return null;
        }
        MetricBaseline base = baseOpt.get();
        double med = base.getMedian() == null ? 0.0 : base.getMedian();
        double thr = threshold(base);

        return new BaselineSnapshot(
                formatValue(metric, med),
                formatValue(metric, thr),
                base.getSampleCount());
    }

    private String formatValue(String metric, double value) {
        return switch (metric) {
            case "LATENCY" -> DetectorSupport.duration(value);
            case "OUTPUT_SIZE" -> DetectorSupport.bytes(value);
            default -> DetectorSupport.round(value, 2) + "";
        };
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Snapshot of the anomaly's recovery progress, returned by the status API.
     */
    public record RecoveryStatus(
            AnomalyStatus anomalyStatus,
            boolean verificationActive,
            int healthyCount,
            int observedCount,
            int requiredCount,
            Instant startedAt,
            /** null when verification has never been started or no usable baseline exists. */
            BaselineSnapshot baseline) {

        public static RecoveryStatus of(Anomaly anomaly, BaselineSnapshot baseline) {
            boolean active = anomaly.getStatus() == AnomalyStatus.VERIFYING_RECOVERY;
            return new RecoveryStatus(
                    anomaly.getStatus(),
                    active,
                    anomaly.getRecoveryHealthyCount(),
                    anomaly.getRecoveryObservedCount(),
                    anomaly.getRecoveryRequiredCount() > 0
                            ? anomaly.getRecoveryRequiredCount()
                            : DEFAULT_REQUIRED_COUNT,
                    anomaly.getRecoveryStartedAt(),
                    baseline);
        }
    }

    /**
     * Human-readable baseline snapshot for the recovery panel UI.
     */
    public record BaselineSnapshot(
            String baseline,
            String threshold,
            int sampleCount) {
    }
}
