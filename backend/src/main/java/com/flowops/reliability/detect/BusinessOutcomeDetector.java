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
 *
 * <p>Implements:
 * <ul>
 *   <li><strong>Zero-Output Detector:</strong> Triggers when an execution or node that historically
 *       produces N items (baseline median > 0) suddenly produces 0 items, despite technical success
 *       (status = SUCCEEDED). Does NOT trigger if 0 items is normal (median = 0) or if item telemetry
 *       is unavailable (pruned / uninstrumented).</li>
 *   <li><strong>Output-Collapse Detector:</strong> Triggers when item count drops significantly below
 *       median baseline (> 3 MADs or > 80% collapse) for nodes that historically produce substantial output.</li>
 *   <li><strong>HTTP 200 Empty Response:</strong> Flags unexpected empty HTTP payloads on 200 OK.</li>
 * </ul>
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

        String form = sig.get("form") instanceof String s ? s : "";
        Object arrayLenObj = sig.get("arrayLength");
        Integer arrayLength = arrayLenObj instanceof Number n ? n.intValue() : null;

        // Resolve item count from signature or metric
        Integer itemCount = null;
        if (sig.get("itemCount") instanceof Number n) {
            itemCount = n.intValue();
        } else if ("EMPTY".equalsIgnoreCase(form) || (arrayLength != null && arrayLength == 0)) {
            itemCount = 0;
        } else if (arrayLength != null) {
            itemCount = arrayLength;
        }

        // 1. Zero-output detector for items
        if (itemCount != null && itemCount == 0) {
            if (DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                    && sizeBaseline.getMedian() != null && sizeBaseline.getMedian() > 0.0) {
                double median = sizeBaseline.getMedian();
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("kind", "zero-output");
                evidence.put("medianItems", median);
                evidence.put("actualItems", 0);
                evidence.put("sampleCount", sizeBaseline.getSampleCount());
                evidence.put("status", obs.getStatus().name());

                findings.add(new Finding(
                        AnomalyType.OUTPUT,
                        obs.getNodeId(),
                        "OUTPUT_SIZE",
                        "expected output ≈ " + Math.round(median) + " items",
                        "produced 0 items despite technical success",
                        5.0,
                        0.90,
                        evidence,
                        "OUTPUT:" + obs.getNodeId() + ":zero_output"
                ));
                return findings;
            }
        }

        // 2. Output-collapse detector for items (> 3 MADs or > 80% collapse)
        if (itemCount != null && itemCount > 0) {
            if (DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                    && sizeBaseline.getMedian() != null && sizeBaseline.getMedian() >= 5.0) {
                double median = sizeBaseline.getMedian();
                double sigma = DetectorSupport.robustSigma(sizeBaseline);
                double deviation = (median - itemCount) / Math.max(1.0, sigma);
                boolean droppedByMads = deviation >= 3.0 && (itemCount <= (median * 0.50));
                boolean droppedByRatio = itemCount <= (median * 0.20);

                if (droppedByMads || droppedByRatio) {
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("kind", "output-collapse");
                    evidence.put("medianItems", median);
                    evidence.put("actualItems", itemCount);
                    evidence.put("dropRatio", DetectorSupport.round(1.0 - ((double) itemCount / median), 2));
                    evidence.put("sigma", DetectorSupport.round(sigma, 2));
                    evidence.put("sampleCount", sizeBaseline.getSampleCount());

                    findings.add(new Finding(
                            AnomalyType.OUTPUT,
                            obs.getNodeId(),
                            "OUTPUT_SIZE",
                            "expected output near median " + Math.round(median) + " items",
                            "output collapsed to " + itemCount + " items (" + Math.round((1.0 - ((double) itemCount / median)) * 100) + "% drop despite success)",
                            Math.max(3.5, deviation),
                            0.85,
                            evidence,
                            "OUTPUT:" + obs.getNodeId() + ":output_collapse"
                    ));
                    return findings;
                }
            }
        }

        // 3. Detect zero records / empty list outcome when HTTP returned 200 OK
        Integer httpStatus = obs.getHttpStatus();
        if (httpStatus != null && httpStatus == 200) {
            if ("EMPTY".equalsIgnoreCase(form) || (arrayLength != null && arrayLength == 0)) {
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

        // 4. Detect byte-level output volume collapse for legacy payloads
        if (itemCount == null && DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                && sizeBaseline.getMedian() != null && sizeBaseline.getMedian() >= 200.0
                && obs.getOutputSize() != null) {

            double median = sizeBaseline.getMedian();
            double actual = obs.getOutputSize().doubleValue();

            if (actual < (median * 0.10) && actual <= 20.0) {
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
