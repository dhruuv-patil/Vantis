package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.NodeExecutionMetric;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Detects silent stalls where a workflow with a regular scheduled cadence or historical
 * inter-run interval stops running without raising an execution failure (reliability pivot).
 */
@Component
public class SilentStallDetector {

    private final ReliabilityProperties properties;

    public SilentStallDetector(ReliabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * Evaluates whether a workflow has silently stalled based on its execution history.
     *
     * @param workflowId the workflow under evaluation
     * @param recentExecutions recent execution metrics ordered by startedAt descending
     * @param now the current evaluation time
     * @return a finding if a silent stall is detected, else empty
     */
    public Optional<Finding> detect(UUID workflowId, List<NodeExecutionMetric> recentExecutions, Instant now) {
        if (recentExecutions == null || recentExecutions.size() < 3) {
            // Cold-start guard: require at least 3 historical runs to compute inter-run intervals
            return Optional.empty();
        }

        // Extract distinct execution start times in ascending order
        List<Instant> runTimes = new ArrayList<>();
        UUID lastExecId = null;
        for (NodeExecutionMetric m : recentExecutions) {
            if (m.getStartedAt() != null && (lastExecId == null || !lastExecId.equals(m.getExecutionId()))) {
                runTimes.add(m.getStartedAt());
                lastExecId = m.getExecutionId();
            }
        }

        if (runTimes.size() < 3) {
            return Optional.empty();
        }

        Collections.sort(runTimes);
        Instant lastRun = runTimes.get(runTimes.size() - 1);
        Duration elapsed = Duration.between(lastRun, now);
        if (elapsed.isNegative() || elapsed.isZero()) {
            return Optional.empty();
        }

        // Calculate consecutive inter-run intervals in seconds
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < runTimes.size(); i++) {
            long diffSec = Duration.between(runTimes.get(i - 1), runTimes.get(i)).getSeconds();
            if (diffSec > 0) {
                intervals.add((double) diffSec);
            }
        }

        if (intervals.size() < 2) {
            return Optional.empty();
        }

        Collections.sort(intervals);
        double medianIntervalSec = intervals.get(intervals.size() / 2);

        // Require a reasonable recurring cadence (at least 60s median)
        if (medianIntervalSec < 60.0) {
            return Optional.empty();
        }

        // Calculate MAD for interval regularity check
        List<Double> absDevs = new ArrayList<>();
        for (Double interval : intervals) {
            absDevs.add(Math.abs(interval - medianIntervalSec));
        }
        Collections.sort(absDevs);
        double madSec = absDevs.get(absDevs.size() / 2);

        // If the workflow is completely sporadic (MAD > median * 1.5), it has no predictable cadence
        if (madSec > medianIntervalSec * 1.5) {
            return Optional.empty();
        }

        // Expected maximum interval: 1.5x median interval + scheduler delay grace period
        long gracePeriodSec = Math.max(300, properties.schedulerDelay().getSeconds() * 2);
        double expectedMaxSec = (medianIntervalSec * 1.5) + gracePeriodSec;

        long elapsedSec = elapsed.getSeconds();
        if (elapsedSec > expectedMaxSec) {
            double deviation = (elapsedSec - medianIntervalSec) / Math.max(60.0, Math.max(madSec * 1.4826, medianIntervalSec * 0.5));
            double sampleFactor = DetectorSupport.clamp01((double) runTimes.size() / properties.minSampleCount());
            double deviationFactor = DetectorSupport.clamp01(deviation / 3.0);
            double confidence = DetectorSupport.round(Math.max(0.70, sampleFactor * deviationFactor), 2);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("kind", "silent-stall");
            evidence.put("medianIntervalMinutes", DetectorSupport.round(medianIntervalSec / 60.0, 1));
            evidence.put("elapsedMinutes", DetectorSupport.round(elapsedSec / 60.0, 1));
            evidence.put("expectedMaxMinutes", DetectorSupport.round(expectedMaxSec / 60.0, 1));
            evidence.put("lastRunAt", lastRun.toString());
            evidence.put("sampleCount", runTimes.size());

            String expected = "run expected every ≈ " + Math.round(medianIntervalSec / 60.0) + "m (grace up to "
                    + Math.round(expectedMaxSec / 60.0) + "m)";
            String actual = "no execution in " + Math.round(elapsedSec / 60.0) + "m since " + lastRun;

            return Optional.of(new Finding(
                    AnomalyType.VOLUME,
                    null, // workflow-scoped
                    "SILENT_STALL",
                    expected,
                    actual,
                    DetectorSupport.round(deviation, 2),
                    confidence,
                    evidence,
                    "VOLUME:" + workflowId + ":silent_stall"
            ));
        }

        return Optional.empty();
    }
}
