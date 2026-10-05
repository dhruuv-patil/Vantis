package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Detects "technically successful but suspicious business outcome" conditions.
 * Examples:
 * - HTTP 200 or node SUCCEEDED, but zero records / items processed
 * - Output volume or record count suddenly collapses (e.g., normally 500 records, now 0 or 3)
 *
 * Implements a domain-agnostic framework by inspecting structural output signatures,
 * record counters, array lengths, and output sizes against learned baselines.
 */
@Component
public class BusinessOutcomeDetector {

    private final ReliabilityProperties properties;

    public BusinessOutcomeDetector(ReliabilityProperties properties) {
        this.properties = properties;
    }

    public List<Finding> detect(MetricBaseline sizeBaseline, NodeExecutionMetric obs) {
        List<Finding> findings = new ArrayList<>();
        if (obs.getStatus() != NodeRunStatus.SUCCEEDED) {
            return findings;
        }

        Map<String, Object> sig = obs.getOutputSignature();
        if (sig == null) {
            return findings;
        }

        // 1. Detect zero records / empty list outcome when HTTP returned 200 OK
        Integer httpStatus = obs.getHttpStatus();
        String form = sig.get("form") instanceof String s ? s : "";
        Object arrayLenObj = sig.get("arrayLength");
        Integer arrayLength = arrayLenObj instanceof Number n ? n.intValue() : null;

        if (httpStatus != null && httpStatus == 200) {
            if ("EMPTY".equalsIgnoreCase(form) || (arrayLength != null && arrayLength == 0)) {
                // If baseline says normal output is substantial
                if (DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                        && sizeBaseline.getMedian() != null && sizeBaseline.getMedian() > 500.0) {
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("kind", "http-200-empty-records");
                    evidence.put("httpStatus", 200);
                    evidence.put("observedForm", form);
                    evidence.put("arrayLength", arrayLength != null ? arrayLength : 0);
                    evidence.put("baselineMedianBytes", sizeBaseline.getMedian());
                    evidence.put("observedBytes", obs.getOutputSize());

                    findings.add(new Finding(
                            AnomalyType.OUTPUT,
                            obs.getNodeId(),
                            "BUSINESS_OUTCOME",
                            "HTTP 200 with substantial response payload (median " + Math.round(sizeBaseline.getMedian()) + " B)",
                            "HTTP 200 OK received but zero records returned (empty response)",
                            4.5,
                            0.85,
                            evidence,
                            "BUSINESS_OUTCOME:" + obs.getNodeId() + ":empty_200"));
                }
            }
        }

        // 2. Detect output collapse (> 90% drop compared to baseline median)
        if (DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                && sizeBaseline.getMedian() != null && sizeBaseline.getMedian() >= 200.0
                && obs.getOutputSize() != null) {

            double median = sizeBaseline.getMedian();
            double actual = obs.getOutputSize().doubleValue();

            if (actual < (median * 0.10) && actual <= 20.0) { // Dropped below 10% of median and is tiny (<20 bytes)
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("kind", "output-volume-collapse");
                evidence.put("baselineMedianBytes", median);
                evidence.put("actualBytes", actual);
                evidence.put("ratio", DetectorSupport.round(actual / median, 3));

                findings.add(new Finding(
                        AnomalyType.OUTPUT,
                        obs.getNodeId(),
                        "BUSINESS_OUTCOME",
                        "expected output size near median " + Math.round(median) + " B",
                        "output collapsed to " + Math.round(actual) + " B (>90% drop despite success)",
                        4.0,
                        0.80,
                        evidence,
                        "BUSINESS_OUTCOME:" + obs.getNodeId() + ":volume_collapse"));
            }
        }

        return findings;
    }
}
