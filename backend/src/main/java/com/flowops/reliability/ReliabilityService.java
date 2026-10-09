package com.flowops.reliability;

import com.flowops.ai.AiServiceClient;
import com.flowops.api.ReliabilityEnvelopes;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowExecution;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the reliability API.
 *
 * <p>Tenant isolation is derived from the authenticated principal.
 * Client-supplied organization IDs are never trusted.</p>
 */
@Service
public class ReliabilityService {

    private static final Logger log =
            LoggerFactory.getLogger(ReliabilityService.class);

    private final AnomalyRepository anomalies;
    private final NodeExecutionMetricRepository metrics;
    private final MetricBaselineRepository baselines;
    private final WorkflowRepository workflows;
    private final IntegrationWorkflowRepository integrationWorkflows;
    private final WorkflowExecutionRepository executions;
    private final AiServiceClient aiClient;
    private final RecoveryVerificationService recoveryService;
    private final com.flowops.repository.WorkflowVersionRepository versions;
    private final com.flowops.repository.NodeExecutionAttemptRepository nodeAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public ReliabilityService(
            AnomalyRepository anomalies,
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            WorkflowRepository workflows,
            IntegrationWorkflowRepository integrationWorkflows,
            WorkflowExecutionRepository executions,
            AiServiceClient aiClient,
            RecoveryVerificationService recoveryService,
            com.flowops.repository.WorkflowVersionRepository versions,
            com.flowops.repository.NodeExecutionAttemptRepository nodeAttempts) {

        this.anomalies = anomalies;
        this.metrics = metrics;
        this.baselines = baselines;
        this.workflows = workflows;
        this.integrationWorkflows = integrationWorkflows;
        this.executions = executions;
        this.aiClient = aiClient;
        this.recoveryService = recoveryService;
        this.versions = versions;
        this.nodeAttempts = nodeAttempts;
    }

    // =========================================================================
    // Anomalies
    // =========================================================================

    @Transactional(readOnly = true)
    public ReliabilityEnvelopes.AnomaliesResponse listAnomalies(
            AuthenticatedUser user,
            Optional<AnomalyStatus> status,
            Optional<UUID> workflowId) {

        var principal = AuthenticatedUser.require();
        UUID organizationId = principal.organizationId();

        List<Anomaly> anomalyList;

        if (workflowId.isPresent()) {
            UUID requestedWorkflowId = workflowId.get();

            workflows.findByIdAndOrganizationId(
                            requestedWorkflowId,
                            organizationId)
                    .orElseThrow(
                            () -> new ApiException(
                                    ErrorCode.WORKFLOW_NOT_FOUND));

            anomalyList =
                    anomalies.findByOrganizationIdAndWorkflowIdOrderByDetectedAtDesc(
                            organizationId,
                            requestedWorkflowId);

        } else if (status.isPresent()) {
            anomalyList =
                    anomalies.findByOrganizationIdAndStatusOrderByDetectedAtDesc(
                            organizationId,
                            status.get());

        } else {
            anomalyList =
                    anomalies.findByOrganizationIdOrderByDetectedAtDesc(
                            organizationId);
        }

        return new ReliabilityEnvelopes.AnomaliesResponse(
                anomalyList.stream()
                        .map(this::toSummary)
                        .toList());
    }

    @Transactional(readOnly = true)
    public ReliabilityEnvelopes.AnomalyDetailResponse getAnomaly(
            AuthenticatedUser user,
            UUID anomalyId) {

        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(
                                anomalyId,
                                principal.organizationId())
                        .orElseThrow(
                                () -> new ApiException(
                                        ErrorCode.ANOMALY_NOT_FOUND));

        return new ReliabilityEnvelopes.AnomalyDetailResponse(
                toDetail(anomaly));
    }

    @Transactional
    public ReliabilityEnvelopes.AcknowledgeResponse acknowledge(
            AuthenticatedUser user,
            UUID anomalyId,
            String note) {

        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(
                                anomalyId,
                                principal.organizationId())
                        .orElseThrow(
                                () -> new ApiException(
                                        ErrorCode.ANOMALY_NOT_FOUND));

        anomaly.acknowledge();

        log.info(
                "Anomaly {} acknowledged by user {} in organization {}{}",
                anomalyId,
                principal.userId(),
                principal.organizationId(),
                note == null || note.isBlank()
                        ? ""
                        : " note=" + note);

        return new ReliabilityEnvelopes.AcknowledgeResponse(
                toDetail(anomalies.save(anomaly)));
    }

    @Transactional
    public ReliabilityEnvelopes.ResolveResponse resolve(
            AuthenticatedUser user,
            UUID anomalyId,
            String note) {

        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(
                                anomalyId,
                                principal.organizationId())
                        .orElseThrow(
                                () -> new ApiException(
                                        ErrorCode.ANOMALY_NOT_FOUND));

        anomaly.resolve();

        log.info(
                "Anomaly {} resolved by user {} in organization {}{}",
                anomalyId,
                principal.userId(),
                principal.organizationId(),
                note == null || note.isBlank()
                        ? ""
                        : " note=" + note);

        return new ReliabilityEnvelopes.ResolveResponse(
                toDetail(anomalies.save(anomaly)));
    }

    @Transactional
    public ReliabilityEnvelopes.FalsePositiveResponse markFalsePositive(
            AuthenticatedUser user,
            UUID anomalyId,
            String reason) {

        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(
                                anomalyId,
                                principal.organizationId())
                        .orElseThrow(
                                () -> new ApiException(
                                        ErrorCode.ANOMALY_NOT_FOUND));

        anomaly.markFalsePositive();

        log.info(
                "Anomaly {} marked false-positive by user {} in organization {}{}",
                anomalyId,
                principal.userId(),
                principal.organizationId(),
                reason == null || reason.isBlank()
                        ? ""
                        : " reason=" + reason);

        return new ReliabilityEnvelopes.FalsePositiveResponse(
                toDetail(anomalies.save(anomaly)));
    }

    @Transactional
    public ReliabilityEnvelopes.VerifyRecoveryResponse startVerification(
            AuthenticatedUser user,
            UUID anomalyId,
            Integer requiredCount) {

        Anomaly anomaly =
                recoveryService.startVerification(
                        user,
                        anomalyId,
                        requiredCount);

        return new ReliabilityEnvelopes.VerifyRecoveryResponse(
                toDetail(anomaly));
    }

    @Transactional(readOnly = true)
    public ReliabilityEnvelopes.RecoveryStatusResponse getRecoveryStatus(
            AuthenticatedUser user,
            UUID anomalyId) {

        RecoveryVerificationService.RecoveryStatus snapshot =
                recoveryService.getRecoveryStatus(user, anomalyId);

        ReliabilityEnvelopes.BaselineSnapshot baselineEnv = null;

        if (snapshot.baseline() != null) {
            baselineEnv =
                    new ReliabilityEnvelopes.BaselineSnapshot(
                            snapshot.baseline().baseline(),
                            snapshot.baseline().threshold(),
                            snapshot.baseline().sampleCount());
        }

        ReliabilityEnvelopes.RecoveryStatus env =
                new ReliabilityEnvelopes.RecoveryStatus(
                        snapshot.anomalyStatus(),
                        snapshot.verificationActive(),
                        snapshot.healthyCount(),
                        snapshot.observedCount(),
                        snapshot.requiredCount(),
                        snapshot.startedAt(),
                        baselineEnv);

        return new ReliabilityEnvelopes.RecoveryStatusResponse(env);
    }

    // =========================================================================
    // Workflow health
    // =========================================================================

    @Transactional(readOnly = true)
    public ReliabilityEnvelopes.WorkflowHealthResponse getWorkflowHealth(
            AuthenticatedUser user,
            UUID workflowId) {

        var principal = AuthenticatedUser.require();
        UUID organizationId = principal.organizationId();

        // Name may come from a FlowOps-native workflow OR (for external/provider
        // workflows) from an integration_workflows mapping. We 404 only when neither
        // record exists, so external anomalies/health resolve the real remote name.
        String workflowName =
                workflows.findByIdAndOrganizationId(
                                workflowId,
                                organizationId)
                        .map(Workflow::getName)
                        .orElse(null);

        if (workflowName == null) {
            workflowName =
                    integrationWorkflows
                            .findByIdAndOrganizationId(
                                    workflowId,
                                    organizationId)
                            .map(w ->
                                    (w.getName() != null
                                            && !w.getName().isBlank())
                                            ? w.getName()
                                            : null)
                            .orElseThrow(
                                    () -> new ApiException(
                                            ErrorCode.WORKFLOW_NOT_FOUND));
        }

        List<Anomaly> openAnomalies =
                anomalies.findByOrganizationIdAndWorkflowIdAndStatus(
                        organizationId,
                        workflowId,
                        AnomalyStatus.OPEN);

        long totalExecutions =
                executions.countByOrganizationIdAndWorkflowId(
                        organizationId,
                        workflowId);

        long succeededExecutions =
                executions.countSucceededByOrganizationIdAndWorkflowId(
                        organizationId,
                        workflowId);

        long failedExecutions =
                executions.countFailedByOrganizationIdAndWorkflowId(
                        organizationId,
                        workflowId);

        double successRate =
                totalExecutions > 0
                        ? (double) succeededExecutions / totalExecutions
                        : 1.0;

        int reliabilityScore =
                computeReliabilityScore(
                        successRate,
                        openAnomalies);

        Instant lastExecutionAt =
                executions
                        .findTopByWorkflowIdAndOrganizationIdOrderByFinishedAtDesc(
                                workflowId,
                                organizationId)
                        .map(WorkflowExecution::getFinishedAt)
                        .orElse(null);

        return new ReliabilityEnvelopes.WorkflowHealthResponse(
                new ReliabilityEnvelopes.WorkflowHealth(
                        workflowId,
                        workflowName,
                        reliabilityScore,
                        totalExecutions,
                        succeededExecutions,
                        failedExecutions,
                        successRate,
                        openAnomalies.size(),
                        openAnomalies.stream()
                                .filter(
                                        a -> a.getSeverity()
                                                == AnomalySeverity.CRITICAL)
                                .count(),
                        openAnomalies.stream()
                                .filter(
                                        a -> a.getSeverity()
                                                == AnomalySeverity.HIGH)
                                .count(),
                        openAnomalies.stream()
                                .filter(
                                        a -> a.getSeverity()
                                                == AnomalySeverity.MEDIUM)
                                .count(),
                        openAnomalies.stream()
                                .filter(
                                        a -> a.getSeverity()
                                                == AnomalySeverity.LOW)
                                .count(),
                        lastExecutionAt,
                        Instant.now()));
    }

    private int computeReliabilityScore(
            double successRate,
            List<Anomaly> openAnomalies) {

        double score = 100.0;

        double failureRate = 1.0 - successRate;

        score -= failureRate * 30.0;

        Instant now = Instant.now();

        for (Anomaly anomaly : openAnomalies) {

            double severityWeight =
                    switch (anomaly.getSeverity()) {
                        case CRITICAL -> 15.0;
                        case HIGH -> 10.0;
                        case MEDIUM -> 5.0;
                        case LOW -> 2.0;
                    };

            long daysOld = 0;

            if (anomaly.getDetectedAt() != null) {
                daysOld =
                        Duration.between(
                                        anomaly.getDetectedAt(),
                                        now)
                                .toDays();
            }

            double recencyFactor =
                    Math.max(
                            0.3,
                            1.0 - (daysOld / 30.0));

            score -= severityWeight * recencyFactor;
        }

        return Math.max(
                0,
                Math.min(
                        100,
                        (int) Math.round(score)));
    }

    // =========================================================================
    // Workflow metrics
    // =========================================================================

    @Transactional(readOnly = true)
    public ReliabilityEnvelopes.WorkflowMetricsResponse getWorkflowMetrics(
            AuthenticatedUser user,
            UUID workflowId) {

        var principal = AuthenticatedUser.require();
        UUID organizationId = principal.organizationId();

        workflows.findByIdAndOrganizationId(
                        workflowId,
                        organizationId)
                .orElseThrow(
                        () -> new ApiException(
                                ErrorCode.WORKFLOW_NOT_FOUND));

        // Build time-series from baselines and recent telemetry
        // Latency series (from LATENCY baselines per node) — org-scoped
        List<ReliabilityEnvelopes.MetricSeries> latencySeries =
                baselines
                        .findByOrganizationIdAndWorkflowIdAndMetric(
                                organizationId,
                                workflowId,
                                "LATENCY")
                        .stream()
                        .map(b ->
                                buildMetricSeriesFromBaseline(
                                        b,
                                        "latency_ms"))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .toList();

        // Output size series (from OUTPUT_SIZE baselines per node) — org-scoped
        List<ReliabilityEnvelopes.MetricSeries> outputSizeSeries =
                baselines
                        .findByOrganizationIdAndWorkflowIdAndMetric(
                                organizationId,
                                workflowId,
                                "OUTPUT_SIZE")
                        .stream()
                        .map(b ->
                                buildMetricSeriesFromBaseline(
                                        b,
                                        "output_bytes"))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .toList();

        // Volume series (from VOLUME baseline - workflow-scoped) — org-scoped
        List<ReliabilityEnvelopes.MetricSeries> volumeSeries =
                baselines
                        .findByOrganizationIdAndWorkflowIdAndNodeIdAndMetric(
                                organizationId,
                                workflowId,
                                "",
                                "VOLUME")
                        .map(this::buildVolumeSeries)
                        .map(List::of)
                        .orElse(List.of());

        // Success rate series
        List<ReliabilityEnvelopes.MetricSeries> successRateSeries =
                buildSuccessRateSeries(workflowId);

        // Anomaly frequency series
        List<ReliabilityEnvelopes.MetricSeries> anomalyFrequencySeries =
                buildAnomalyFrequencySeries(workflowId);

        // Reliability score history
        List<ReliabilityEnvelopes.MetricSeries> reliabilityScoreHistory =
                buildReliabilityScoreHistory(workflowId);

        return new ReliabilityEnvelopes.WorkflowMetricsResponse(
                new ReliabilityEnvelopes.WorkflowMetrics(
                        workflowId,
                        latencySeries,
                        volumeSeries,
                        successRateSeries,
                        anomalyFrequencySeries,
                        reliabilityScoreHistory));
    }

    private Optional<ReliabilityEnvelopes.MetricSeries>
            buildMetricSeriesFromBaseline(
                    MetricBaseline baseline,
                    String metricLabel) {

        if (baseline.getP50() == null
                && baseline.getP95() == null
                && baseline.getMedian() == null) {
            return Optional.empty();
        }

        // For now, return a single point showing the current baseline values.
        // A full implementation would track baseline evolution over time.
        List<ReliabilityEnvelopes.Point> points = new ArrayList<>();
        Instant now = Instant.now();

        if (baseline.getMedian() != null) {
            points.add(
                    new ReliabilityEnvelopes.Point(
                            now,
                            baseline.getMedian(),
                            "p50 (median)"));
        }

        if (baseline.getP95() != null) {
            points.add(
                    new ReliabilityEnvelopes.Point(
                            now,
                            baseline.getP95(),
                            "p95"));
        }

        if (baseline.getP99() != null) {
            points.add(
                    new ReliabilityEnvelopes.Point(
                            now,
                            baseline.getP99(),
                            "p99"));
        }

        return Optional.of(
                new ReliabilityEnvelopes.MetricSeries(
                        metricLabel,
                        baseline.getNodeId(),
                        points));
    }

    private ReliabilityEnvelopes.MetricSeries buildVolumeSeries(
            MetricBaseline baseline) {

        // VOLUME baseline stores recent per-window counts in signature.
        List<ReliabilityEnvelopes.Point> points = new ArrayList<>();

        if (baseline.getSignature() != null) {
            Object recent =
                    baseline.getSignature().get("recentCounts");

            if (recent instanceof List<?> counts) {
                for (int i = 0; i < counts.size(); i++) {
                    Object count = counts.get(i);

                    if (count instanceof Number n) {
                        points.add(
                                new ReliabilityEnvelopes.Point(
                                        Instant.now()
                                                .minusSeconds(
                                                        (counts.size() - i)
                                                                * 300),
                                        n.doubleValue(),
                                        "executions"));
                    }
                }
            }
        }

        return new ReliabilityEnvelopes.MetricSeries(
                "executions_per_window",
                "",
                points);
    }

    private List<ReliabilityEnvelopes.MetricSeries>
            buildSuccessRateSeries(UUID workflowId) {

        // Query recent executions and bucket by time window.
        // For now return empty - requires time-series aggregation query.
        return List.of();
    }

    private List<ReliabilityEnvelopes.MetricSeries>
            buildAnomalyFrequencySeries(UUID workflowId) {

        // Query anomalies grouped by time window.
        // For now return empty - requires time-series aggregation query.
        return List.of();
    }

    private List<ReliabilityEnvelopes.MetricSeries>
            buildReliabilityScoreHistory(UUID workflowId) {

        // Would track reliability score over time.
        // For now return empty - requires historical persistence.
        return List.of();
    }

    // =========================================================================
    // AI investigation
    // =========================================================================

    @Transactional
    public ReliabilityEnvelopes.InvestigateResponse investigateAnomaly(
            AuthenticatedUser user,
            UUID anomalyId) {

        var principal = AuthenticatedUser.require();

        Anomaly anomaly =
                anomalies.findByIdAndOrganizationId(
                                anomalyId,
                                principal.organizationId())
                        .orElseThrow(
                                () -> new ApiException(
                                        ErrorCode.ANOMALY_NOT_FOUND));

        Map<String, Object> evidencePackage =
                buildEvidencePackage(anomaly);

        ReliabilityEnvelopes.AIInvestigationResult result =
                aiClient.investigateAnomaly(evidencePackage);

        Map<String, Object> updatedEvidence =
                new HashMap<>(
                        anomaly.getEvidence() != null
                                ? anomaly.getEvidence()
                                : Map.of());

        Map<String, Object> investigation =
                new HashMap<>();

        investigation.put(
                "summary",
                result.summary());

        investigation.put(
                "likelyCauses",
                result.likelyCauses());

        investigation.put(
                "evidence",
                result.evidence());

        investigation.put(
                "impact",
                result.impact());

        investigation.put(
                "recommendedActions",
                result.recommendedActions());

        investigation.put(
                "confidence",
                result.confidence());

        investigation.put(
                "configured",
                result.configured());

        investigation.put(
                "model",
                result.model());

        investigation.put(
                "generatedAt",
                result.generatedAt());

        updatedEvidence.put(
                "investigation",
                investigation);

        anomaly.setEvidence(updatedEvidence);

        anomalies.save(anomaly);

        return new ReliabilityEnvelopes.InvestigateResponse(
                result);
    }

    private Map<String, Object> buildEvidencePackage(
            Anomaly anomaly) {

        Map<String, Object> evidencePackage =
                new HashMap<>();

        evidencePackage.put(
                "anomalyId",
                anomaly.getId() != null
                        ? anomaly.getId().toString()
                        : null);

        evidencePackage.put(
                "type",
                anomaly.getType() != null
                        ? anomaly.getType().name()
                        : null);

        evidencePackage.put(
                "severity",
                anomaly.getSeverity() != null
                        ? anomaly.getSeverity().name()
                        : null);

        evidencePackage.put(
                "metric",
                anomaly.getMetric());

        evidencePackage.put(
                "expected",
                anomaly.getExpectedValue());

        evidencePackage.put(
                "actual",
                anomaly.getActualValue());

        evidencePackage.put(
                "deviation",
                anomaly.getDeviation());

        evidencePackage.put(
                "metricScope",
                anomaly.getNodeId() != null
                        ? "NODE"
                        : "WORKFLOW");

        if (anomaly.getNodeId() != null) {
            evidencePackage.put(
                    "metricNodeId",
                    anomaly.getNodeId());
        }

        evidencePackage.put(
                "confidence",
                anomaly.getConfidence());

        evidencePackage.put(
                "affectedExecutions",
                anomaly.getAffectedExecutions());

        evidencePackage.put(
                "evidence",
                anomaly.getEvidence());

        evidencePackage.put(
                "detectedAt",
                anomaly.getDetectedAt() != null
                        ? anomaly.getDetectedAt().toString()
                        : null);

        // Add affected step/node information.
        if (anomaly.getNodeId() != null) {

            evidencePackage.put(
                    "affectedNodeId",
                    anomaly.getNodeId());

            Optional<NodeExecutionMetric> metricOpt =
                    metrics.findByExecutionIdAndNodeIdAndOrganizationId(
                            anomaly.getExecutionId(),
                            anomaly.getNodeId(),
                            anomaly.getOrganizationId());

            if (metricOpt.isPresent()) {

                NodeExecutionMetric m =
                        metricOpt.get();

                evidencePackage.put(
                        "affectedNodeType",
                        m.getNodeType());

                Map<String, Object> anomEvidence =
                        anomaly.getEvidence();

                if (anomEvidence != null) {

                    if (anomEvidence.containsKey("kind")) {
                        evidencePackage.put(
                                "findingKind",
                                anomEvidence.get("kind"));
                    }

                    if (anomEvidence.containsKey("expectedType")) {
                        evidencePackage.put(
                                "expectedType",
                                anomEvidence.get("expectedType"));
                    }

                    if (anomEvidence.containsKey("actualType")) {
                        evidencePackage.put(
                                "actualType",
                                anomEvidence.get("actualType"));
                    }

                    if (anomEvidence.containsKey("field")) {
                        evidencePackage.put(
                                "affectedField",
                                anomEvidence.get("field"));
                    }

                    if (anomEvidence.containsKey(
                            "severityRationale")) {
                        evidencePackage.put(
                                "severityRationale",
                                anomEvidence.get(
                                        "severityRationale"));
                    }
                }
            }
        }

        if (anomaly.getExecutionId() != null) {

            Optional<WorkflowExecution> execution =
                    executions.findByIdAndOrganizationId(
                            anomaly.getExecutionId(),
                            anomaly.getOrganizationId());

            if (execution.isPresent()) {

                WorkflowExecution e =
                        execution.get();

                Map<String, Object> executionData =
                        new HashMap<>();

                executionData.put(
                        "id",
                        e.getId() != null
                                ? e.getId().toString()
                                : null);

                executionData.put(
                        "status",
                        e.getStatus() != null
                                ? e.getStatus().name()
                                : null);

                executionData.put(
                        "startedAt",
                        e.getStartedAt() != null
                                ? e.getStartedAt().toString()
                                : null);

                executionData.put(
                        "finishedAt",
                        e.getFinishedAt() != null
                                ? e.getFinishedAt().toString()
                                : null);

                executionData.put(
                        "workflowVersionId",
                        e.getWorkflowVersionId() != null
                                ? e.getWorkflowVersionId().toString()
                                : null);

                executionData.put(
                        "versionNumber",
                        e.getVersionNumber());

                executionData.put(
                        "workerId",
                        e.getWorkerId());

                executionData.put(
                        "queueWaitMs",
                        e.getQueueWaitMs());

                if (nodeAttempts != null) {

                    List<com.flowops.domain.NodeExecutionAttempt>
                            attempts =
                            nodeAttempts
                                    .findByExecutionIdOrderByStartedAtAsc(
                                            e.getId());

                    if (!attempts.isEmpty()) {

                        List<Map<String, Object>> attemptList =
                                new ArrayList<>();

                        for (
                                com.flowops.domain.NodeExecutionAttempt att
                                        : attempts) {

                            Map<String, Object> a =
                                    new HashMap<>();

                            a.put(
                                    "nodeId",
                                    att.getNodeId());

                            a.put(
                                    "attemptNumber",
                                    att.getAttemptNumber());

                            a.put(
                                    "status",
                                    att.getStatus());

                            a.put(
                                    "durationMs",
                                    att.getDurationMs());

                            a.put(
                                    "error",
                                    att.getError());

                            attemptList.add(a);
                        }

                        executionData.put(
                                "attempts",
                                attemptList);
                    }
                }

                if (versions != null
                        && e.getWorkflowVersionId() != null) {

                    versions.findById(
                            e.getWorkflowVersionId())
                            .ifPresent(v -> {

                                evidencePackage.put(
                                        "workflowVersionNumber",
                                        v.getVersionNumber());

                                evidencePackage.put(
                                        "workflowVersionCreatedAt",
                                        v.getCreatedAt() != null
                                                ? v.getCreatedAt().toString()
                                                : null);
                            });
                }

                // The raw trigger payload is customer data (PII, tokens) and is deliberately
                // NOT sent to the external LLM. Telemetry below carries structure only.

                // Add relevant node metrics for this execution — org-scoped via workflow.
                List<NodeExecutionMetric> executionMetrics =
                        metrics
                                .findByOrganizationIdAndWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(
                                        anomaly.getOrganizationId(),
                                        anomaly.getWorkflowId(),
                                        Instant.EPOCH);

                if (!executionMetrics.isEmpty()) {

                    List<Map<String, Object>> nodeMetrics =
                            new ArrayList<>();

                    for (NodeExecutionMetric m
                            : executionMetrics) {

                        Map<String, Object> nodeData =
                                new HashMap<>();

                        nodeData.put(
                                "nodeId",
                                m.getNodeId());

                        nodeData.put(
                                "nodeType",
                                m.getNodeType());

                        nodeData.put(
                                "status",
                                m.getStatus() != null
                                        ? m.getStatus().name()
                                        : null);

                        nodeData.put(
                                "durationMs",
                                m.getDurationMs());

                        nodeData.put(
                                "retryCount",
                                m.getRetryCount());

                        nodeData.put(
                                "inputSize",
                                m.getInputSize());

                        nodeData.put(
                                "outputSize",
                                m.getOutputSize());

                        nodeData.put(
                                "errorType",
                                m.getErrorType());

                        // errorMessage is omitted: driver/HTTP errors can embed
                        // hosts, SQL and credentials.

                        if (m.getOutputSignature() != null) {
                            nodeData.put(
                                    "outputSignature",
                                    m.getOutputSignature());
                        }

                        nodeMetrics.add(nodeData);
                    }

                    executionData.put(
                            "nodeMetrics",
                            nodeMetrics);
                }

                evidencePackage.put(
                        "execution",
                        executionData);
            }
        }

        return evidencePackage;
    }

    // =========================================================================
    // Mapping
    // =========================================================================

    /**
     * Resolves a display name for a workflow referenced by an anomaly.
     *
     * Internal anomalies carry a workflows.id; external provider anomalies carry
     * an integration_workflows.id that has no FlowOps-native workflow.
     */
    private String resolveWorkflowName(
            UUID workflowId,
            UUID organizationId) {

        try {

            Optional<Workflow> workflow =
                    workflows.findByIdAndOrganizationId(
                            workflowId,
                            organizationId);

            if (workflow.isPresent()) {
                return workflow.get().getName();
            }

        } catch (RuntimeException ex) {

            log.debug(
                    "Unable to resolve internal workflow name for {}",
                    workflowId,
                    ex);
        }

        try {

            Optional<com.flowops.domain.IntegrationWorkflow>
                    integrationWorkflow =
                    integrationWorkflows
                            .findByIdAndOrganizationId(
                                    workflowId,
                                    organizationId);

            if (integrationWorkflow.isPresent()) {

                String name =
                        integrationWorkflow.get().getName();

                if (name != null && !name.isBlank()) {
                    return name;
                }
            }

        } catch (RuntimeException ex) {

            log.debug(
                    "Unable to resolve integration workflow name for {}",
                    workflowId,
                    ex);
        }

        return "Workflow "
                + workflowId.toString().substring(0, 8);
    }

    private ReliabilityEnvelopes.AnomalySummary toSummary(
            Anomaly anomaly) {

        String workflowName =
                resolveWorkflowName(
                        anomaly.getWorkflowId(),
                        anomaly.getOrganizationId());

        return new ReliabilityEnvelopes.AnomalySummary(
                anomaly.getId(),
                anomaly.getWorkflowId(),
                workflowName,
                anomaly.getNodeId(),
                null,
                anomaly.getType(),
                anomaly.getSeverity(),
                anomaly.getStatus(),
                anomaly.getMetric(),
                anomaly.getExpectedValue(),
                anomaly.getActualValue(),
                anomaly.getDeviation(),
                anomaly.getConfidence(),
                anomaly.getAffectedExecutions(),
                anomaly.getDetectedAt(),
                anomaly.getUpdatedAt());
    }

    private ReliabilityEnvelopes.AnomalyDetail toDetail(
            Anomaly anomaly) {

        String workflowName =
                resolveWorkflowName(
                        anomaly.getWorkflowId(),
                        anomaly.getOrganizationId());

        return new ReliabilityEnvelopes.AnomalyDetail(
                anomaly.getId(),
                anomaly.getOrganizationId(),
                anomaly.getWorkflowId(),
                workflowName,
                anomaly.getNodeId(),
                null,
                anomaly.getExecutionId(),
                anomaly.getType(),
                anomaly.getSeverity(),
                anomaly.getStatus(),
                anomaly.getMetric(),
                anomaly.getExpectedValue(),
                anomaly.getActualValue(),
                anomaly.getDeviation(),
                anomaly.getConfidence(),
                anomaly.getAffectedExecutions(),
                anomaly.getEvidence(),
                anomaly.getDedupKey(),
                anomaly.getDetectedAt(),
                anomaly.getUpdatedAt(),
                anomaly.getCreatedAt());
    }
}