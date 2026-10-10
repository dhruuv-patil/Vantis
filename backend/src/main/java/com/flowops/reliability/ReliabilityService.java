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
import com.flowops.common.security.SensitiveDataRedactor;
import com.flowops.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<String, CachedInvestigation> investigationCache = new ConcurrentHashMap<>();

    private record CachedInvestigation(ReliabilityEnvelopes.AIInvestigationResult result, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(Duration.ofHours(1)));
        }
    }

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

        String stateHash = computeInvestigationStateHash(anomaly);
        String cacheKey = principal.organizationId() + ":" + anomalyId + ":" + stateHash;
        CachedInvestigation cached = investigationCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return new ReliabilityEnvelopes.InvestigateResponse(cached.result());
        }

        Map<String, Object> evidencePackage =
                buildEvidencePackage(anomaly);

        Map<String, Object> sanitizedEvidencePackage =
                SensitiveDataRedactor.redactMap(evidencePackage);

        ReliabilityEnvelopes.AIInvestigationResult result;
        try {
            result = aiClient.investigateAnomaly(sanitizedEvidencePackage);
            if (!result.configured()) {
                result = buildRuleBasedFallback(anomaly, sanitizedEvidencePackage);
            }
        } catch (Exception ex) {
            log.warn("AI service call failed for anomaly {}, utilizing rule-based fallback: {}", anomalyId, ex.getMessage());
            result = buildRuleBasedFallback(anomaly, sanitizedEvidencePackage);
        }

        investigationCache.put(cacheKey, new CachedInvestigation(result, Instant.now()));

        Map<String, Object> updatedEvidence =
                new HashMap<>(
                        anomaly.getEvidence() != null
                                ? anomaly.getEvidence()
                                : Map.of());

        Map<String, Object> investigation =
                new HashMap<>();

        investigation.put("summary", result.summary());
        investigation.put("likelyCauses", result.likelyCauses());
        investigation.put("evidence", result.evidence());
        investigation.put("impact", result.impact());
        investigation.put("recommendedActions", result.recommendedActions());
        investigation.put("confidence", result.confidence());
        investigation.put("configured", result.configured());
        investigation.put("model", result.model());
        investigation.put("generatedAt", result.generatedAt());
        investigation.put("evidenceCitations", result.evidenceCitations());
        investigation.put("suggestedFix", result.suggestedFix());
        investigation.put("verificationChecklist", result.verificationChecklist());
        investigation.put("fallback", result.fallback());

        updatedEvidence.put(
                "investigation",
                investigation);

        anomaly.setEvidence(updatedEvidence);

        anomalies.save(anomaly);

        return new ReliabilityEnvelopes.InvestigateResponse(
                result);
    }

    String computeInvestigationStateHash(Anomaly anomaly) {
        return (anomaly.getExecutionId() != null ? anomaly.getExecutionId().toString() : "no-exec")
                + ":" + anomaly.getAffectedExecutions()
                + ":" + (anomaly.getActualValue() != null ? anomaly.getActualValue() : "none")
                + ":" + (anomaly.getStatus() != null ? anomaly.getStatus().name() : "none");
    }

    void clearInvestigationCache() {
        investigationCache.clear();
    }

    ReliabilityEnvelopes.AIInvestigationResult buildRuleBasedFallback(
            Anomaly anomaly,
            Map<String, Object> evidencePackage) {

        String typeName = anomaly.getType() != null ? anomaly.getType().name() : "UNKNOWN";
        String actual = anomaly.getActualValue() != null ? anomaly.getActualValue() : "N/A";
        String expected = anomaly.getExpectedValue() != null ? anomaly.getExpectedValue() : "N/A";
        String metric = anomaly.getMetric() != null ? anomaly.getMetric() : "telemetry";

        String kind = null;
        if (anomaly.getEvidence() != null && anomaly.getEvidence().get("kind") != null) {
            kind = String.valueOf(anomaly.getEvidence().get("kind"));
        }

        String summary = String.format(
                "Rule-based diagnostic: %s anomaly detected on %s. Observed %s against expected %s.",
                kind != null ? kind : typeName, metric, actual, expected);

        List<ReliabilityEnvelopes.LikelyCause> causes = new ArrayList<>();
        List<ReliabilityEnvelopes.EvidenceItem> evidenceItems = new ArrayList<>();
        List<ReliabilityEnvelopes.RecommendedAction> actions = new ArrayList<>();
        List<String> citations = new ArrayList<>();
        citations.add("actual");
        citations.add("expected");
        if (anomaly.getDeviation() > 0) {
            citations.add("deviation");
        }

        String suggestedFix;
        List<String> checklist = new ArrayList<>();

        if ("ZERO_OUTPUT".equalsIgnoreCase(kind)) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Workflow completed technically but produced 0 output items",
                    "data-quality",
                    0.85,
                    "Downstream filtering or upstream empty collection not distinguished",
                    List.of("actual", "expected")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "metric",
                    "Execution finished with 0 output items vs expected " + expected,
                    "anomaly.actualValue",
                    "Zero output collapse detected"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Inspect filter nodes and upstream collection sources",
                    "Determine whether upstream data source was empty or prematurely filtered",
                    "low",
                    "low"));
            suggestedFix = "Verify upstream query returns rows and check branch filter conditions for unintended exclusions.";
            checklist.add("Verify next execution produces >= 1 output item");
            checklist.add("Check provider execution log for empty response array");
        } else if ("OUTPUT_COLLAPSE".equalsIgnoreCase(kind)) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Output volume dropped precipitously below historical baseline",
                    "data-quality",
                    0.80,
                    "Possible partial batch drop or upstream throttling",
                    List.of("actual", "expected", "deviation")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "metric",
                    "Output volume collapsed to " + actual + " vs baseline " + expected,
                    "anomaly.deviation",
                    "Sudden item drop exceeds MAD threshold"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Compare input volume against output volume across recent runs",
                    "Isolate whether input decreased or items were dropped during processing",
                    "low",
                    "medium"));
            suggestedFix = "Inspect batch processing and pagination loops to ensure all items are being collected.";
            checklist.add("Verify output item count returns to baseline expected range");
        } else if ("SCHEMA_DRIFT".equalsIgnoreCase(kind)) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Output payload structure drifted from established schema baseline",
                    "code-change",
                    0.85,
                    "External API contract change or missing required fields",
                    List.of("actual", "expected")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "signature",
                    "Structural signature changed from baseline " + expected + " to " + actual,
                    "anomaly.actualValue",
                    "Incompatible field type or missing keys"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Inspect downstream schema dependencies for breaking field changes",
                    "Prevent downstream processing failures due to unexpected payload shape",
                    "medium",
                    "medium"));
            suggestedFix = "Update schema mapping or add fallback default values for missing/renamed fields.";
            checklist.add("Verify downstream nodes parse new payload structure without errors");
            checklist.add("Confirm structural signature matches expected schema");
        } else if ("LATENCY_SPIKE".equalsIgnoreCase(kind) || anomaly.getType() == com.flowops.domain.AnomalyType.LATENCY) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Execution duration significantly exceeded historical median",
                    "infrastructure",
                    0.75,
                    "Specific slow node or external service response latency",
                    List.of("actual", "expected", "deviation")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "metric",
                    "Execution duration was " + actual + " vs baseline " + expected,
                    "anomaly.actualValue",
                    "Latency exceeds threshold"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Review step-level durations and external HTTP call timeouts",
                    "Identify dominant latency contributor among workflow steps",
                    "low",
                    "low"));
            suggestedFix = "Set tighter timeouts and exponential backoff retry policies on external HTTP requests.";
            checklist.add("Verify subsequent executions complete within normal duration range");
        } else if ("ERROR_SPIKE".equalsIgnoreCase(kind)) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Consecutive or elevated execution failure rate detected",
                    "external-dependency",
                    0.85,
                    "External authentication, rate limiting, or service disruption",
                    List.of("actual", "expected")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "signature",
                    "Failure rate rose to " + actual,
                    "anomaly.actualValue",
                    "Consecutive failures triggered error-spike detector"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Inspect execution error logs and provider response codes",
                    "Identify specific failure reason (e.g. 401, 429, 500)",
                    "low",
                    "low"));
            suggestedFix = "Verify credentials and inspect external service status pages for outages.";
            checklist.add("Verify 3 consecutive executions succeed with status SUCCESS");
        } else if ("SILENT_STALL".equalsIgnoreCase(kind)) {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Workflow silent stall detected — no executions recorded within expected schedule interval",
                    "workflow",
                    0.85,
                    "Disabled cron trigger, paused workflow, or webhook listener failure",
                    List.of("actual", "expected")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "metric",
                    "Time since last run was " + actual + " vs expected interval " + expected,
                    "anomaly.actualValue",
                    "Schedule interval exceeded threshold"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Check external platform workflow active status and trigger configuration",
                    "Verify webhook endpoint or cron timer is active and enabled",
                    "low",
                    "low"));
            suggestedFix = "Activate workflow trigger on external provider or trigger manual test run.";
            checklist.add("Verify at least 1 new execution arrives within schedule window");
        } else {
            causes.add(new ReliabilityEnvelopes.LikelyCause(
                    "Workflow telemetry deviated from expected baseline",
                    "unknown",
                    0.60,
                    "Root cause requires manual inspection of execution telemetry",
                    List.of("actual", "expected")));
            evidenceItems.add(new ReliabilityEnvelopes.EvidenceItem(
                    "metric",
                    "Observed " + actual + " vs expected " + expected,
                    "anomaly.actualValue",
                    "Statistical deviation detected"));
            actions.add(new ReliabilityEnvelopes.RecommendedAction(
                    "Inspect recent execution logs for this workflow",
                    "Correlate deviation with changes in input or dependencies",
                    "low",
                    "low"));
            suggestedFix = "Inspect workflow execution history to identify failure pattern.";
            checklist.add("Verify workflow telemetry returns to normal range");
        }

        return new ReliabilityEnvelopes.AIInvestigationResult(
                summary,
                causes,
                evidenceItems,
                "Workflow reliability affected; potential data quality or delivery disruption.",
                actions,
                0.75,
                false,
                "rule-based-fallback",
                Instant.now(),
                citations,
                suggestedFix,
                checklist,
                true);
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

        // Add workflow details and platform type
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("id", anomaly.getWorkflowId() != null ? anomaly.getWorkflowId().toString() : null);
        workflowData.put("name", resolveWorkflowName(anomaly.getWorkflowId(), anomaly.getOrganizationId()));
        String platformType = "INTERNAL";
        if (anomaly.getWorkflowId() != null) {
            Optional<com.flowops.domain.IntegrationWorkflow> iwOpt =
                    integrationWorkflows.findByIdAndOrganizationId(anomaly.getWorkflowId(), anomaly.getOrganizationId());
            if (iwOpt.isPresent()) {
                com.flowops.domain.IntegrationWorkflow iw = iwOpt.get();
                workflowData.put("externalWorkflowId", iw.getProviderWorkflowId());
                workflowData.put("status", iw.getStatus());
                workflowData.put("monitoringEnabled", iw.isMonitoringEnabled());
                if (iw.getMetadata() != null && iw.getMetadata().containsKey("provider")) {
                    platformType = String.valueOf(iw.getMetadata().get("provider"));
                }
            }
        }
        workflowData.put("platformType", platformType);
        evidencePackage.put("workflow", workflowData);
        evidencePackage.put("platformType", platformType);

        // Add baseline metrics
        if (anomaly.getMetric() != null && anomaly.getWorkflowId() != null) {
            Optional<MetricBaseline> baseOpt = anomaly.getNodeId() != null
                    ? baselines.findByOrganizationIdAndWorkflowIdAndNodeIdAndMetric(
                            anomaly.getOrganizationId(), anomaly.getWorkflowId(), anomaly.getNodeId(), anomaly.getMetric())
                    : baselines.findByOrganizationIdAndWorkflowIdAndMetric(
                            anomaly.getOrganizationId(), anomaly.getWorkflowId(), anomaly.getMetric())
                            .stream()
                            .filter(b -> b.getNodeId() == null)
                            .findFirst();
            if (baseOpt.isPresent()) {
                MetricBaseline b = baseOpt.get();
                Map<String, Object> baseData = new HashMap<>();
                baseData.put("median", b.getMedian());
                baseData.put("mad", b.getMad());
                baseData.put("mean", b.getMean());
                baseData.put("stddev", b.getStddev());
                baseData.put("sampleCount", b.getSampleCount());
                evidencePackage.put("baseline", baseData);
            }
        }

        // Add recovery status if available
        if (recoveryService != null && anomaly.getId() != null) {
            try {
                RecoveryVerificationService.RecoveryStatus snap = recoveryService.getRecoveryStatus(
                        null,
                        anomaly.getId());
                if (snap != null) {
                    Map<String, Object> recData = new HashMap<>();
                    recData.put("status", snap.anomalyStatus() != null ? snap.anomalyStatus().name() : null);
                    recData.put("active", snap.verificationActive());
                    recData.put("healthyCount", snap.healthyCount());
                    recData.put("observedCount", snap.observedCount());
                    recData.put("requiredCount", snap.requiredCount());
                    evidencePackage.put("recoveryStatus", recData);
                }
            } catch (Exception ignored) {
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