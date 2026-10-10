package com.flowops.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response envelopes for the reliability API (reliability pivot, M4).
 * Mirrors the same envelope pattern as {@link Envelopes}.
 */
public final class ReliabilityEnvelopes {

    private ReliabilityEnvelopes() {
    }

    // =========================================================================
    // Anomaly list / detail
    // =========================================================================

    /** {@code GET /api/reliability/anomalies} */
    public record AnomaliesResponse(List<AnomalySummary> anomalies) {
    }

    /** One line in the anomalies table. */
    public record AnomalySummary(
            UUID id,
            UUID workflowId,
            String workflowName,
            String nodeId,
            String nodeType,
            AnomalyType type,
            AnomalySeverity severity,
            AnomalyStatus status,
            String metric,
            String expectedValue,
            String actualValue,
            double deviation,
            double confidence,
            int affectedExecutions,
            Instant detectedAt,
            Instant updatedAt) {
    }

    /** {@code GET /api/reliability/anomalies/{id}} */
    public record AnomalyDetailResponse(AnomalyDetail anomaly) {
    }

    /** Full anomaly detail for the anomaly detail page. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnomalyDetail(
            UUID id,
            UUID organizationId,
            UUID workflowId,
            String workflowName,
            String nodeId,
            String nodeType,
            UUID executionId,
            AnomalyType type,
            AnomalySeverity severity,
            AnomalyStatus status,
            String metric,
            String expectedValue,
            String actualValue,
            double deviation,
            double confidence,
            int affectedExecutions,
            Map<String, Object> evidence,
            String dedupKey,
            Instant detectedAt,
            Instant updatedAt,
            Instant createdAt) {
    }

    /** {@code POST /api/reliability/anomalies/{id}/acknowledge} */
    public record AcknowledgeRequest(String note) {
    }

    public record AcknowledgeResponse(AnomalyDetail anomaly) {
    }

    /** {@code POST /api/reliability/anomalies/{id}/resolve} */
    public record ResolveRequest(String note) {
    }

    public record ResolveResponse(AnomalyDetail anomaly) {
    }

    /** {@code POST /api/reliability/anomalies/{id}/false-positive} */
    public record FalsePositiveRequest(String reason) {
    }

    public record FalsePositiveResponse(AnomalyDetail anomaly) {
    }

    /** {@code POST /api/reliability/anomalies/{id}/verify-recovery} */
    public record VerifyRecoveryRequest(Integer requiredCount) {
    }

    public record VerifyRecoveryResponse(AnomalyDetail anomaly) {
    }

    /** {@code GET /api/reliability/anomalies/{id}/recovery} */
    public record RecoveryStatusResponse(RecoveryStatus snapshot) {
    }

    /** Recovery snapshot mirroring the service record. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecoveryStatus(
            AnomalyStatus anomalyStatus,
            boolean verificationActive,
            int healthyCount,
            int observedCount,
            int requiredCount,
            Instant startedAt,
            BaselineSnapshot baseline) {
    }

    public record BaselineSnapshot(
            String baseline,
            String threshold,
            int sampleCount) {
    }

    // =========================================================================
    // Workflow health / reliability score
    // =========================================================================

    /** {@code GET /api/reliability/workflows/{id}/health} */
    public record WorkflowHealthResponse(WorkflowHealth health) {
    }

    /** Computed reliability score (0–100) plus supporting metrics. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowHealth(
            UUID workflowId,
            String workflowName,
            int reliabilityScore,
            long totalExecutions,
            long succeededExecutions,
            long failedExecutions,
            double successRate,
            long openAnomalies,
            long criticalAnomalies,
            long highAnomalies,
            long mediumAnomalies,
            long lowAnomalies,
            Instant lastExecutionAt,
            Instant scoreComputedAt) {
    }

    // =========================================================================
    // Workflow metrics (for charts)
    // =========================================================================

    /** {@code GET /api/reliability/workflows/{id}/metrics} */
    public record WorkflowMetricsResponse(WorkflowMetrics metrics) {
    }

    /** Time-series metrics for charts (latency, volume, success rate, anomaly frequency). */
    public record WorkflowMetrics(
            UUID workflowId,
            List<MetricSeries> latency,
            List<MetricSeries> volume,
            List<MetricSeries> successRate,
            List<MetricSeries> anomalyFrequency,
            List<MetricSeries> reliabilityScoreHistory) {
    }

    /** A single time-series series. */
    public record MetricSeries(
            String metric,
            String nodeId,
            List<Point> points) {
    }

    /** One data point (timestamp + value). */
    public record Point(
            Instant at,
            double value,
            String label) {
    }

    // =========================================================================
    // AI investigation
    // =========================================================================

    /** {@code POST /api/reliability/anomalies/{id}/investigate} */
    public record InvestigateRequest(Map<String, Object> context) {
    }

    public record InvestigateResponse(AIInvestigationResult result) {
    }

    /** Structured AI investigation result (mirrors ai-service schema). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AIInvestigationResult(
            String summary,
            List<LikelyCause> likelyCauses,
            List<EvidenceItem> evidence,
            String impact,
            List<RecommendedAction> recommendedActions,
            double confidence,
            boolean configured,
            String model,
            Instant generatedAt,
            List<String> evidenceCitations,
            String suggestedFix,
            List<String> verificationChecklist,
            boolean fallback) {

        public AIInvestigationResult(
                String summary,
                List<LikelyCause> likelyCauses,
                List<EvidenceItem> evidence,
                String impact,
                List<RecommendedAction> recommendedActions,
                double confidence,
                boolean configured,
                String model,
                Instant generatedAt) {
            this(summary, likelyCauses, evidence, impact, recommendedActions, confidence, configured, model, generatedAt,
                    List.of(), null, List.of(), false);
        }
    }

    public record LikelyCause(
            String cause,
            String category,
            double confidence,
            String uncertainty,
            List<String> citations) {

        public LikelyCause(
                String cause,
                String category,
                double confidence,
                String uncertainty) {
            this(cause, category, confidence, uncertainty, List.of());
        }
    }

    public record EvidenceItem(
            String type,
            String description,
            String source,
            String inference) {
    }

    public record RecommendedAction(
            String action,
            String rationale,
            String risk,
            String effort) {
    }
}