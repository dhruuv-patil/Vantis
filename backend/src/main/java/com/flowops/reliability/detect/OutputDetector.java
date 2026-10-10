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
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Flags "successful but wrong" node output (reliability pivot, M3).
 *
 * <p>The run succeeded — the workflow did not fail — yet the output's <em>structure</em>
 * deviated from what the {@code OUTPUT_SCHEMA} baseline learned: a field that was always
 * present went missing, a field's type changed, a previously-real field is suddenly null,
 * an unexpected field appeared, the whole output came back NULL/EMPTY, or the output
 * grew far beyond the {@code OUTPUT_SIZE} baseline. All comparisons are on the structural
 * signature (field names, types, null flags, sizes) — never on values.
 *
 * <p>Suppressed until the schema baseline clears warm-up ({@code minSampleCount}), so a
 * change seen in the first handful of runs is not (yet) an anomaly.
 *
 * <p>Enhanced with recursive structural comparison to detect drift in nested objects,
 * nested missing/new fields, nested type changes, and array structure changes.
 */
@Component
public class OutputDetector {

    private final ReliabilityProperties properties;

    public OutputDetector(ReliabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * @param schemaBaseline the node's {@code OUTPUT_SCHEMA} baseline (may be null/warming up)
     * @param sizeBaseline   the node's {@code OUTPUT_SIZE} baseline (may be null/warming up)
     * @param obs            the just-observed node run
     * @return output findings (possibly several), or empty when normal / warming up
     */
    public List<Finding> detect(MetricBaseline schemaBaseline, MetricBaseline sizeBaseline,
            NodeExecutionMetric obs) {
        List<Finding> findings = new ArrayList<>();
        if (obs.getStatus() != NodeRunStatus.SUCCEEDED) {
            return findings;
        }
        if (DetectorSupport.usable(schemaBaseline, properties.minSampleCount())) {
            findings.addAll(schemaFindings(schemaBaseline, obs));
        }
        if (DetectorSupport.usable(sizeBaseline, properties.minSampleCount())
                && obs.getOutputSize() != null) {
            sizeFinding(sizeBaseline, obs).ifPresent(findings::add);
        }
        return findings;
    }

    // ---- schema (structure) findings ---------------------------------------

    private List<Finding> schemaFindings(MetricBaseline base, NodeExecutionMetric obs) {
        List<Finding> out = new ArrayList<>();
        Map<String, Object> expected = base.getSignature();
        Map<String, Object> actual = obs.getOutputSignature();
        if (expected == null || actual == null) {
            return out;
        }
        String obsForm = getForm(actual);
        int sampleCount = base.getSampleCount();

        // Determine expected form from baseline - if baseline has fields, it's OBJECT
        String expForm = getForm(expected);
        if ((expForm == null || expForm.isEmpty() || "UNKNOWN".equals(expForm)) && hasFields(expected)) {
            expForm = "OBJECT";
        }

        // Whole-output regression: expected an object full of fields, got nothing.
        if ("NULL".equals(obsForm) || "EMPTY".equals(obsForm)) {
            out.add(finding(base, obs, "output", null,
                    "expected an object with fields",
                    "output came back " + obsForm,
                    6.0, 0.9, map("kind", "output-" + obsForm.toLowerCase(),
                            "expectedForm", expForm, "actualForm", obsForm)));
        } else if (!"UNKNOWN".equals(expForm) && !"UNKNOWN".equals(obsForm) && !expForm.equals(obsForm)) {
            // Form mismatch (e.g., expected OBJECT, got ARRAY)
            out.add(finding(base, obs, "output", null,
                    "expected " + expForm,
                    "got " + obsForm,
                    5.0, 0.85, map("kind", "form-mismatch",
                            "expectedForm", expForm, "actualForm", obsForm)));
        }

        // Recursively compare structures
        compareStructures(base, obs, expected, actual, "", out, sampleCount);

        return out;
    }

    /**
     * Checks if a signature has fields (indicating OBJECT form).
     */
    private boolean hasFields(Map<String, Object> sig) {
        if (sig == null) return false;
        Object fields = sig.get("fields");
        return fields instanceof Map<?, ?> m && !m.isEmpty();
    }

    /**
     * Recursively compares expected (baseline) and actual (observed) structures.
     * Handles nested objects, arrays, and scalar types.
     */
    private void compareStructures(MetricBaseline base, NodeExecutionMetric obs,
            Map<String, Object> expected, Map<String, Object> actual,
            String pathPrefix, List<Finding> out, int sampleCount) {

        String expForm = getForm(expected);
        String obsForm = getForm(actual);

        // If forms differ at this level and both are known, report it
        if (!expForm.equals(obsForm) && !"UNKNOWN".equals(expForm) && !"UNKNOWN".equals(obsForm)) {
            String path = pathPrefix.isEmpty() ? "output" : pathPrefix;
            out.add(finding(base, obs, "field", path,
                    "expected " + expForm,
                    "got " + obsForm,
                    4.0, 0.8, map("kind", "form-mismatch",
                            "expectedForm", expForm, "actualForm", obsForm, "path", path)));
            return; // Can't compare deeper if forms differ
        }

        // Handle scalar comparison when baseline has old format (no explicit form)
        // If baseline has "types" field, it's a scalar field signature
        if (expected.containsKey("types") && actual.containsKey("type")) {
            compareScalars(base, obs, expected, actual, pathPrefix, out, sampleCount);
            return;
        }

        switch (expForm) {
            case "OBJECT" -> compareObjects(base, obs, expected, actual, pathPrefix, out, sampleCount);
            case "ARRAY" -> compareArrays(base, obs, expected, actual, pathPrefix, out, sampleCount);
            case "SCALAR" -> compareScalars(base, obs, expected, actual, pathPrefix, out, sampleCount);
            default -> {
                // If baseline is UNKNOWN but actual has a form, use actual's form
                if ("UNKNOWN".equals(expForm) && !"UNKNOWN".equals(obsForm)) {
                    compareStructures(base, obs, actual, actual, pathPrefix, out, sampleCount);
                }
                // NULL, EMPTY, UNKNOWN - no deeper comparison needed
            }
        }
    }

    private void compareObjects(MetricBaseline base, NodeExecutionMetric obs,
            Map<String, Object> expected, Map<String, Object> actual,
            String pathPrefix, List<Finding> out, int sampleCount) {

        Map<String, Object> expFields = getFields(expected);
        Map<String, Object> obsFields = getFields(actual);

        // Check for missing fields
        for (Map.Entry<String, Object> e : expFields.entrySet()) {
            String fieldName = e.getKey();
            String fullPath = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;

            @SuppressWarnings("unchecked")
            Map<String, Object> expField = (Map<String, Object>) e.getValue();

            Object obsFieldRaw = obsFields.get(fieldName);
            if (obsFieldRaw == null) {
                // Field missing entirely - check if it was always present in baseline
                // New signature format: check "type" and "null" fields
                boolean alwaysPresent = isFieldAlwaysPresent(expField);
                if (alwaysPresent) {
                    out.add(finding(base, obs, "field", fullPath,
                            "field \"" + fullPath + "\" is always present",
                            "\"" + fullPath + "\" missing from output",
                            5.0, 0.95, map("kind", "field-missing", "field", fullPath)));
                }
                continue;
            }

            // Field exists - recursively compare its structure
            @SuppressWarnings("unchecked")
            Map<String, Object> obsField = (Map<String, Object>) obsFieldRaw;
            compareStructures(base, obs, expField, obsField, fullPath, out, sampleCount);
        }

        // Check for unexpected new fields
        for (String fieldName : obsFields.keySet()) {
            if (!expFields.containsKey(fieldName)) {
                String fullPath = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;
                out.add(finding(base, obs, "field", fullPath,
                        "no field \"" + fullPath + "\" expected",
                        "unexpected field \"" + fullPath + "\" in output",
                        3.0, 0.6, map("kind", "field-unexpected", "field", fullPath)));
            }
        }
    }

    private void compareArrays(MetricBaseline base, NodeExecutionMetric obs,
            Map<String, Object> expected, Map<String, Object> actual,
            String pathPrefix, List<Finding> out, int sampleCount) {

        Integer expSize = getSize(expected);
        Integer obsSize = getSize(actual);
        String path = pathPrefix.isEmpty() ? "output" : pathPrefix;

        // Check array size change
        if (expSize != null && obsSize != null && !expSize.equals(obsSize)) {
            out.add(finding(base, obs, "field", path,
                    "array of size " + expSize,
                    "array of size " + obsSize,
                    3.5, 0.7, map("kind", "array-size-change",
                            "expectedSize", expSize, "actualSize", obsSize, "path", path)));
        }

        // Compare item structures if both have items
        Object expItems = expected.get("items");
        Object obsItems = actual.get("items");
        if (expItems instanceof Map<?, ?> && obsItems instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> expItemMap = (Map<String, Object>) expItems;
            @SuppressWarnings("unchecked")
            Map<String, Object> obsItemMap = (Map<String, Object>) obsItems;
            compareStructures(base, obs, expItemMap, obsItemMap, path + "[]", out, sampleCount);
        } else if (expItems != null && obsItems == null) {
            out.add(finding(base, obs, "field", path + "[]",
                    "array with structured items",
                    "array with unstructured/empty items",
                    3.0, 0.65, map("kind", "array-items-structure-change", "path", path + "[]")));
        } else if (expItems == null && obsItems != null) {
            out.add(finding(base, obs, "field", path + "[]",
                    "array without structured items (or empty)",
                    "array with structured items",
                    3.0, 0.65, map("kind", "array-items-structure-change", "path", path + "[]")));
        }

        // Check element type diversity changes
        Object expDiversity = expected.get("elementTypeDiversity");
        Object obsDiversity = actual.get("elementTypeDiversity");
        if (expDiversity instanceof Map<?, ?> && obsDiversity instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> expDiv = (Map<String, Integer>) expDiversity;
            @SuppressWarnings("unchecked")
            Map<String, Integer> obsDiv = (Map<String, Integer>) obsDiversity;
            if (!expDiv.equals(obsDiv)) {
                out.add(finding(base, obs, "field", path,
                        "array element types: " + expDiv,
                        "array element types: " + obsDiv,
                        3.0, 0.6, map("kind", "array-element-type-diversity-change",
                                "expectedDiversity", expDiv, "actualDiversity", obsDiv, "path", path)));
            }
        } else if (expDiversity != null && obsDiversity == null) {
            out.add(finding(base, obs, "field", path,
                    "array with element type diversity: " + expDiversity,
                    "array without tracked element type diversity",
                    2.5, 0.55, map("kind", "array-element-type-diversity-lost", "path", path)));
        } else if (expDiversity == null && obsDiversity != null) {
            out.add(finding(base, obs, "field", path,
                    "array without tracked element type diversity",
                    "array with element type diversity: " + obsDiversity,
                    2.5, 0.55, map("kind", "array-element-type-diversity-new", "path", path)));
        }
    }

    private void compareScalars(MetricBaseline base, NodeExecutionMetric obs,
            Map<String, Object> expected, Map<String, Object> actual,
            String pathPrefix, List<Finding> out, int sampleCount) {

        // Handle both old format (type field from baseline) and new format (type field from observed)
        String expType = getScalarTypeFromBaseline(expected);
        String obsType = str(actual.get("type"));
        String path = pathPrefix.isEmpty() ? "output" : pathPrefix;

        if (!expType.equals(obsType) && !"null".equals(obsType)) {
            out.add(finding(base, obs, "field", path,
                    "field \"" + path + "\" was " + expType,
                    "\"" + path + "\" became " + obsType,
                    4.0, 0.8, map("kind", "scalar-type-change",
                            "expectedType", expType, "actualType", obsType, "path", path)));
        }

        // Null spike detection - handle both formats
        // Old baseline format: nullRate field
        // New baseline format: null field (boolean)
        double nullRate = getNullRateFromBaseline(expected);
        Boolean obsNull = (Boolean) actual.get("null");
        if (obsNull != null && obsNull && nullRate < properties.nullRateSpike()) {
            out.add(finding(base, obs, "field", path,
                    "scalar was rarely null (null-rate " + Math.round(nullRate * 100) + "%)",
                    "scalar is null this run",
                    4.5, 0.85, map("kind", "scalar-null-spike",
                            "baselineNullRate", nullRate, "threshold", properties.nullRateSpike(), "path", path)));
        }
    }

    /**
     * Extracts scalar type from baseline signature (supports both old and new formats).
     */
    private String getScalarTypeFromBaseline(Map<String, Object> field) {
        // New format: "type" field
        if (field.containsKey("type")) {
            return str(field.get("type"));
        }
        // Old format: "types" map with counts
        if (field.containsKey("types")) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> types = (Map<String, Integer>) field.get("types");
            return types.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("unknown");
        }
        return "unknown";
    }

    /**
     * Extracts null rate from baseline signature (supports both old and new formats).
     */
    private double getNullRateFromBaseline(Map<String, Object> field) {
        // New format: "null" boolean field (if present and false, nullRate is 0)
        if (field.containsKey("null")) {
            return Boolean.TRUE.equals(field.get("null")) ? 1.0 : 0.0;
        }
        // Old format: "nullRate" field
        if (field.containsKey("nullRate")) {
            return numOr(field.get("nullRate"), 0.0);
        }
        // Default: assume no nulls
        return 0.0;
    }

    // ---- helper methods for structure extraction ----

    private String getForm(Map<String, Object> sig) {
        if (sig == null) return "UNKNOWN";
        Object form = sig.get("form");
        if (form != null) {
            return str(form);
        }
        // If no explicit form, infer from fields or shape
        Object fields = sig.get("fields");
        if (fields instanceof Map<?, ?> m && !m.isEmpty()) {
            return "OBJECT";
        }
        Object shape = sig.get("shape");
        if (shape instanceof Map<?, ?> sm && !sm.isEmpty()) {
            return "OBJECT";
        }
        return "UNKNOWN";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getFields(Map<String, Object> sig) {
        if (sig == null) return Map.of();
        Object fields = sig.get("fields");
        if (fields instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Object shape = sig.get("shape");
        if (shape instanceof Map<?, ?> sm) {
            Map<String, Object> adapted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> nested) {
                    Map<String, Object> fieldMeta = new LinkedHashMap<>();
                    fieldMeta.put("form", "OBJECT");
                    fieldMeta.put("shape", nested);
                    adapted.put(String.valueOf(e.getKey()), fieldMeta);
                } else {
                    Map<String, Object> fieldMeta = new LinkedHashMap<>();
                    fieldMeta.put("type", String.valueOf(e.getValue()));
                    fieldMeta.put("null", "null".equals(e.getValue()));
                    fieldMeta.put("form", "SCALAR");
                    adapted.put(String.valueOf(e.getKey()), fieldMeta);
                }
            }
            return adapted;
        }
        return Map.of();
    }

    private Integer getSize(Map<String, Object> sig) {
        if (sig == null) return null;
        Object size = sig.get("size");
        return size instanceof Number n ? n.intValue() : null;
    }

    /**
     * Checks if a field was always present in the baseline.
     * Supports both old signature format (presentRate) and new format (type+null).
     */
    private boolean isFieldAlwaysPresent(Map<String, Object> field) {
        // Old format: presentRate field
        if (field.containsKey("presentRate")) {
            double presentRate = numOr(field.get("presentRate"), 0.0);
            return presentRate >= 0.99;
        }
        // New format: if it has a type and null is false, it was present
        Object type = field.get("type");
        Object isNull = field.get("null");
        if (type != null && isNull != null) {
            return !Boolean.TRUE.equals(isNull);
        }
        // Default: assume present if we can't determine
        return true;
    }

    // ---- size finding ------------------------------------------------------

    private Optional<Finding> sizeFinding(MetricBaseline base, NodeExecutionMetric obs) {
        double median = base.getMedian() == null ? 0.0 : base.getMedian();
        double sigma = DetectorSupport.robustSigma(base);
        double actual = obs.getOutputSize();
        double threshold = median + properties.sensitivity() * sigma;
        if (actual <= threshold) {
            return Optional.empty(); // output shrank — not a reliability problem
        }
        double deviation = (actual - median) / sigma;
        double confidence = DetectorSupport.round(
                DetectorSupport.clamp01(sampleFactor(base.getSampleCount())
                        * DetectorSupport.clamp01(deviation / (2.0 * properties.sensitivity()))),
                2);
        return Optional.of(new Finding(
                AnomalyType.OUTPUT,
                obs.getNodeId(),
                "OUTPUT_SIZE",
                "output ≈ " + DetectorSupport.bytes(median)
                        + " (normal up to " + DetectorSupport.bytes(threshold) + ")",
                DetectorSupport.bytes(actual),
                DetectorSupport.round(deviation, 2),
                confidence,
                withSampleCount(map("kind", "output-size-shift",
                        "medianBytes", Math.round(median),
                        "sigmaBytes", DetectorSupport.round(sigma, 1),
                        "thresholdBytes", Math.round(threshold),
                        "actualBytes", Math.round(actual)), base.getSampleCount()),
                "OUTPUT:" + obs.getNodeId() + ":output:size"));
    }

    private Finding finding(MetricBaseline base, NodeExecutionMetric obs, String scope,
            String field, String expected, String actual, double deviation, double confidence,
            Map<String, Object> evidence) {
        String scopeKey = field == null ? scope : scope + ":" + field;
        double combinedConfidence = DetectorSupport.round(confidence * sampleFactor(base.getSampleCount()), 2);
        return new Finding(
                AnomalyType.OUTPUT,
                obs.getNodeId(),
                "OUTPUT_SCHEMA",
                expected,
                actual,
                DetectorSupport.round(deviation, 2),
                combinedConfidence,
                withSampleCount(evidence, base.getSampleCount()),
                "OUTPUT:" + obs.getNodeId() + ":" + scopeKey);
    }

    private double sampleFactor(int sampleCount) {
        return DetectorSupport.clamp01(sampleCount / (2.0 * properties.minSampleCount()));
    }

    /** Trust signal on every evidence map (never any values). */
    private Map<String, Object> withSampleCount(Map<String, Object> evidence, int sampleCount) {
        Map<String, Object> out = new LinkedHashMap<>(evidence);
        out.put("sampleCount", sampleCount);
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double numOr(Object o, double dflt) {
        return o instanceof Number n ? n.doubleValue() : dflt;
    }
}
