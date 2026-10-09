package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Detects sudden spikes in workflow error rate compared to learned historical baseline (reliability pivot).
 */
@Component
public class ErrorSpikeDetector {

    private final ReliabilityProperties properties;

    public ErrorSpikeDetector(ReliabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * Compares recent execution failure rate against learned baseline.
     *
     * @param baseline the workflow's ERROR_RATE baseline (may be null/warming up)
     * @param recentRows recent node execution metrics in the evaluation window
     * @return an error spike finding if detected, else empty
     */
    public Optional<Finding> detect(MetricBaseline baseline, List<NodeExecutionMetric> recentRows) {
        if (!DetectorSupport.usable(baseline, properties.minSampleCount())) {
            return Optional.empty();
        }
        if (recentRows == null || recentRows.size() < 5) {
            // Require at least 5 observations in recent window to guard against noisy single failures
            return Optional.empty();
        }

        UUID workflowId = baseline.getWorkflowId();
        long total = recentRows.size();
        long failed = 0;
        for (NodeExecutionMetric r : recentRows) {
            if (r.getStatus() == NodeRunStatus.FAILED) {
                failed++;
            }
        }

        double recentErrorRate = (double) failed / total;
        double baselineRate = baseline.getMedian() != null ? baseline.getMedian() : 0.0;
        double sigma = DetectorSupport.robustSigma(baseline);

        // Require at least 2 failures, failure rate >= 20%, and at least 15% increase over baseline
        if (failed >= 2 && recentErrorRate >= 0.20 && (recentErrorRate - baselineRate) >= 0.15) {
            double deviation = (recentErrorRate - baselineRate) / Math.max(0.05, sigma);
            double sampleFactor = DetectorSupport.clamp01((double) baseline.getSampleCount() / (2.0 * properties.minSampleCount()));
            double rateFactor = DetectorSupport.clamp01(recentErrorRate);
            double confidence = DetectorSupport.round(Math.max(0.65, sampleFactor * rateFactor), 2);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("kind", "error-spike");
            evidence.put("baselineErrorRate", DetectorSupport.round(baselineRate, 3));
            evidence.put("recentErrorRate", DetectorSupport.round(recentErrorRate, 3));
            evidence.put("recentFailedCount", failed);
            evidence.put("recentTotalCount", total);
            evidence.put("sampleCount", baseline.getSampleCount());

            String expected = "error rate ≈ " + Math.round(baselineRate * 100) + "%";
            String actual = "error rate jumped to " + Math.round(recentErrorRate * 100) + "% ("
                    + failed + "/" + total + " failed)";

            return Optional.of(new Finding(
                    AnomalyType.BEHAVIORAL,
                    null, // workflow-scoped
                    "ERROR_RATE",
                    expected,
                    actual,
                    DetectorSupport.round(deviation, 2),
                    confidence,
                    evidence,
                    "BEHAVIORAL:" + workflowId + ":error_spike"
            ));
        }

        return Optional.empty();
    }
}
