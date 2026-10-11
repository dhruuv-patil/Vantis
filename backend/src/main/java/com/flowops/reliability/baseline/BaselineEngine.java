package com.flowops.reliability.baseline;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learns what "normal" means for each workflow (reliability pivot, M2).
 *
 * <p>On a fixed schedule it reads the telemetry collected over the rolling
 * {@code baselineWindow}, computes a robust statistical summary (median + MAD +
 * percentiles — resistant to the very outliers we want to detect), and upserts
 * one {@link MetricBaseline} per (workflow, node, metric). No machine learning:
 * simple, explainable statistics.
 *
 * <p>The {@code VOLUME}, {@code ERROR_RATE} and {@code BEHAVIOR} baselines are
 * workflow-scoped ({@code nodeId == ""}); {@code LATENCY}, {@code OUTPUT_SIZE},
 * {@code RETRY_RATE} and {@code OUTPUT_SCHEMA} are per node. A baseline with
 * {@code sampleCount < minSampleCount} is not usable — detectors suppress
 * anomalies for it (warm-up), never inventing a range from a tiny sample.
 *
 * <p>Security: only sizes, structure, and aggregates are stored — never payloads
 * or secrets. A failure recomputing one workflow is swallowed so the sweep continues.
 */
@Service
public class BaselineEngine {

    public static final String LATENCY = "LATENCY";
    public static final String OUTPUT_SIZE = "OUTPUT_SIZE";
    public static final String RETRY_RATE = "RETRY_RATE";
    public static final String ERROR_RATE = "ERROR_RATE";
    public static final String VOLUME = "VOLUME";
    public static final String OUTPUT_SCHEMA = "OUTPUT_SCHEMA";
    public static final String BEHAVIOR = "BEHAVIOR";

    /** Workflow-scoped marker. */
    public static final String WORKFLOW_SCOPE = "";

    private static final Logger log = LoggerFactory.getLogger(BaselineEngine.class);

    private final NodeExecutionMetricRepository metrics;
    private final MetricBaselineRepository baselines;
    private final ReliabilityProperties properties;
    private final AnomalyRepository anomalies;

    public BaselineEngine(
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            ReliabilityProperties properties) {
        this(metrics, baselines, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BaselineEngine(
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            ReliabilityProperties properties,
            @Autowired(required = false) AnomalyRepository anomalies) {
        this.metrics = metrics;
        this.baselines = baselines;
        this.properties = properties;
        this.anomalies = anomalies;
    }

    /**
     * Recomputes baselines for every organization/workflow with recent data.
     */
    @Transactional
    public void recomputeAll() {
        Instant windowStart = Instant.now().minus(properties.baselineWindow());
        Instant windowEnd = Instant.now();

        List<UUID> workflowIds;

        try {
            /*
             * Workflow IDs are UUIDs and therefore globally unique.
             * We discover workflows first, then explicitly scope every
             * telemetry read by workflow ID.
             */
            workflowIds = metrics.findDistinctWorkflowIdsSince(windowStart);
        } catch (RuntimeException e) {
            log.warn(
                    "Baseline sweep could not find workflows to recompute: {}",
                    e.toString());
            return;
        }

        for (UUID workflowId : workflowIds) {
            try {
                recomputeWorkflow(workflowId, windowStart, windowEnd);
            } catch (RuntimeException e) {
                log.warn(
                        "Baseline recompute failed for workflow {}: {}",
                        workflowId,
                        e.toString());
            }
        }

        log.debug(
                "Baseline sweep finished for {} workflow(s)",
                workflowIds.size());
    }

    private void recomputeWorkflow(
            UUID workflowId,
            Instant windowStart,
            Instant windowEnd) {

        List<NodeExecutionMetric> rows =
                metrics.findByWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(
                        workflowId,
                        windowStart);

        if (rows.isEmpty()) {
            return;
        }

        // Exclude executions associated with open anomalies to prevent baseline poisoning
        Set<UUID> openAnomalyExecutionIds = Collections.emptySet();
        if (anomalies != null) {
            try {
                List<com.flowops.domain.Anomaly> openList =
                        anomalies.findByWorkflowIdAndStatus(workflowId, com.flowops.domain.AnomalyStatus.OPEN);
                openAnomalyExecutionIds = openList.stream()
                        .map(com.flowops.domain.Anomaly::getExecutionId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
            } catch (RuntimeException e) {
                log.warn("Could not load open anomalies for workflow {}: {}", workflowId, e.toString());
            }
        }

        if (!openAnomalyExecutionIds.isEmpty()) {
            final Set<UUID> excluded = openAnomalyExecutionIds;
            List<NodeExecutionMetric> cleanRows = rows.stream()
                    .filter(r -> !excluded.contains(r.getExecutionId()))
                    .toList();
            if (!cleanRows.isEmpty()) {
                rows = cleanRows;
            }
        }

        /*
         * workflowId is globally unique, so all rows belong to the same
         * organization. We still verify that invariant rather than silently
         * mixing tenants if corrupted data ever exists.
         */
        UUID organizationId = rows.get(0).getOrganizationId();

        for (NodeExecutionMetric row : rows) {
            if (!organizationId.equals(row.getOrganizationId())) {
                throw new IllegalStateException(
                        "Telemetry for workflow " + workflowId
                                + " contains multiple organizations");
            }
        }

        Map<String, List<NodeExecutionMetric>> byNode = byNode(rows);

        for (Map.Entry<String, List<NodeExecutionMetric>> entry : byNode.entrySet()) {
            String nodeId = entry.getKey();
            List<NodeExecutionMetric> nodeRows = entry.getValue();

            durationBaseline(
                    organizationId,
                    workflowId,
                    nodeId,
                    nodeRows,
                    windowStart,
                    windowEnd);

            sizeBaseline(
                    organizationId,
                    workflowId,
                    nodeId,
                    nodeRows,
                    windowStart,
                    windowEnd);

            retryBaseline(
                    organizationId,
                    workflowId,
                    nodeId,
                    nodeRows,
                    windowStart,
                    windowEnd);

            schemaBaseline(
                    organizationId,
                    workflowId,
                    nodeId,
                    nodeRows,
                    windowStart,
                    windowEnd);
        }

        errorRateBaseline(
                organizationId,
                workflowId,
                rows,
                windowStart,
                windowEnd);

        volumeBaseline(
                organizationId,
                workflowId,
                rows,
                windowStart,
                windowEnd);

        behaviorBaseline(
                organizationId,
                workflowId,
                rows,
                windowStart,
                windowEnd);
    }

    // -------------------------------------------------------------------------
    // Numeric baselines
    // -------------------------------------------------------------------------

    private void durationBaseline(
            UUID org,
            UUID workflowId,
            String nodeId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        List<Double> durations = new ArrayList<>();

        for (NodeExecutionMetric m : rows) {
            if (m.getStatus() == NodeRunStatus.SUCCEEDED
                    && m.getDurationMs() != null) {
                durations.add(m.getDurationMs().doubleValue());
            }
        }

        upsertNumeric(
                org,
                workflowId,
                nodeId,
                LATENCY,
                durations,
                ws,
                we);
    }

    private void sizeBaseline(
            UUID org,
            UUID workflowId,
            String nodeId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        List<Double> sizes = new ArrayList<>();

        for (NodeExecutionMetric m : rows) {
            if (m.getStatus() == NodeRunStatus.SUCCEEDED
                    && m.getOutputSize() != null) {
                sizes.add(m.getOutputSize().doubleValue());
            }
        }

        upsertNumeric(
                org,
                workflowId,
                nodeId,
                OUTPUT_SIZE,
                sizes,
                ws,
                we);
    }

    private void retryBaseline(
            UUID org,
            UUID workflowId,
            String nodeId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        List<Double> retries = new ArrayList<>();

        for (NodeExecutionMetric m : rows) {
            if (m.getStatus() == NodeRunStatus.SUCCEEDED) {
                retries.add((double) m.getRetryCount());
            }
        }

        upsertNumeric(
                org,
                workflowId,
                nodeId,
                RETRY_RATE,
                retries,
                ws,
                we);
    }

    private void errorRateBaseline(
            UUID org,
            UUID workflowId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        List<Double> failed = new ArrayList<>();

        for (NodeExecutionMetric m : rows) {
            failed.add(
                    m.getStatus() == NodeRunStatus.FAILED
                            ? 1.0
                            : 0.0);
        }

        upsertNumeric(
                org,
                workflowId,
                WORKFLOW_SCOPE,
                ERROR_RATE,
                failed,
                ws,
                we);
    }

    private void upsertNumeric(
            UUID org,
            UUID workflowId,
            String nodeId,
            String metric,
            List<Double> values,
            Instant ws,
            Instant we) {

        if (values.isEmpty()) {
            return;
        }

        double[] arr = new double[values.size()];

        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }

        RobustStats.Summary summary = RobustStats.of(arr);

        MetricBaseline base =
                baseline(org, workflowId, nodeId, metric);

        base.setNumeric(summary);
        base.setWindow(ws, we);

        baselines.save(base);
    }

    // -------------------------------------------------------------------------
    // Volume
    // -------------------------------------------------------------------------

    private void volumeBaseline(
            UUID org,
            UUID workflowId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        MetricBaseline base =
                baseline(
                        org,
                        workflowId,
                        WORKFLOW_SCOPE,
                        VOLUME);

        List<Double> recent = recentCounts(base);

        long current = executionCount(rows);

        recent.add((double) current);

        if (recent.size() > 48) {
            recent = new ArrayList<>(
                    recent.subList(
                            recent.size() - 48,
                            recent.size()));
        }

        double[] arr = new double[recent.size()];

        for (int i = 0; i < recent.size(); i++) {
            arr[i] = recent.get(i);
        }

        base.setNumeric(RobustStats.of(arr));
        base.setWindow(ws, we);

        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("recent", recent);

        base.setSignature(sig);

        baselines.save(base);
    }

    private long executionCount(List<NodeExecutionMetric> rows) {
        return rows.stream()
                .map(NodeExecutionMetric::getExecutionId)
                .distinct()
                .count();
    }

    private List<Double> recentCounts(MetricBaseline base) {
        List<Double> recent = new ArrayList<>();

        if (base.getSignature() != null) {
            Object raw = base.getSignature().get("recent");

            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Number n) {
                        recent.add(n.doubleValue());
                    }
                }
            }
        }

        return recent;
    }

    // -------------------------------------------------------------------------
    // Output schema
    // -------------------------------------------------------------------------

    private void schemaBaseline(
            UUID org,
            UUID workflowId,
            String nodeId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        MetricBaseline base =
                baseline(
                        org,
                        workflowId,
                        nodeId,
                        OUTPUT_SCHEMA);

        Map<String, FieldAgg> fields = new HashMap<>();
        int rowCount = 0;

        for (NodeExecutionMetric m : rows) {
            if (m.getStatus() != NodeRunStatus.SUCCEEDED
                    || m.getOutputSignature() == null) {
                continue;
            }

            rowCount++;

            @SuppressWarnings("unchecked")
            Map<String, Object> sig =
                    (Map<String, Object>) m.getOutputSignature();

            Object form = sig.get("form");

            if (!"OBJECT".equals(form)) {
                continue;
            }

            Object rawFields = sig.get("fields");

            if (rawFields instanceof Map<?, ?> fmap) {
                for (Map.Entry<?, ?> e : fmap.entrySet()) {
                    String fieldName = String.valueOf(e.getKey());

                    @SuppressWarnings("unchecked")
                    Map<String, Object> ft =
                            (Map<String, Object>) e.getValue();

                    FieldAgg agg =
                            fields.computeIfAbsent(
                                    fieldName,
                                    k -> new FieldAgg());

                    agg.present++;

                    String type =
                            String.valueOf(
                                    ft.getOrDefault(
                                            "type",
                                            "unknown"));

                    boolean isNull =
                            Boolean.TRUE.equals(ft.get("null"));

                    agg.typeCounts.merge(
                            type,
                            1,
                            Integer::sum);

                    if (isNull) {
                        agg.nullCount++;
                    }
                }
            }
        }

        if (rowCount == 0) {
            return;
        }

        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("rowCount", rowCount);

        Map<String, Object> fieldAggs =
                new LinkedHashMap<>();

        for (Map.Entry<String, FieldAgg> e : fields.entrySet()) {
            Map<String, Object> fa =
                    new LinkedHashMap<>();

            fa.put(
                    "presentRate",
                    round1(
                            e.getValue().present
                                    / (double) rowCount));

            fa.put(
                    "nullRate",
                    round1(
                            e.getValue().nullCount
                                    / (double) rowCount));

            fa.put(
                    "types",
                    e.getValue().typeCounts);

            fieldAggs.put(
                    e.getKey(),
                    fa);
        }

        sig.put("fields", fieldAggs);

        base.setSignature(sig);
        base.setSampleCount(rowCount);
        base.setWindow(ws, we);

        baselines.save(base);
    }

    // -------------------------------------------------------------------------
    // Behavior
    // -------------------------------------------------------------------------

    private void behaviorBaseline(
            UUID org,
            UUID workflowId,
            List<NodeExecutionMetric> rows,
            Instant ws,
            Instant we) {

        MetricBaseline base =
                baseline(
                        org,
                        workflowId,
                        WORKFLOW_SCOPE,
                        BEHAVIOR);

        Map<UUID, List<NodeExecutionMetric>> byExec =
                new LinkedHashMap<>();

        for (NodeExecutionMetric m : rows) {
            byExec.computeIfAbsent(
                    m.getExecutionId(),
                    k -> new ArrayList<>())
                    .add(m);
        }

        int execCount = byExec.size();

        if (execCount == 0) {
            return;
        }

        Map<String, Integer> ranCounts =
                new LinkedHashMap<>();

        for (List<NodeExecutionMetric> execRows : byExec.values()) {
            for (NodeExecutionMetric m : execRows) {
                if (m.getStatus() == NodeRunStatus.SUCCEEDED) {
                    ranCounts.merge(
                            m.getNodeId(),
                            1,
                            Integer::sum);
                }
            }
        }

        Map<String, Object> sig =
                new LinkedHashMap<>();

        sig.put("executions", execCount);

        Map<String, Object> runRates =
                new LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : ranCounts.entrySet()) {
            runRates.put(
                    e.getKey(),
                    round1(
                            e.getValue()
                                    / (double) execCount));
        }

        sig.put("runRates", runRates);

        base.setSignature(sig);
        base.setSampleCount(execCount);
        base.setWindow(ws, we);

        baselines.save(base);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MetricBaseline baseline(
            UUID org,
            UUID workflowId,
            String nodeId,
            String metric) {

        return baselines
                .findByWorkflowIdAndNodeIdAndMetric(
                        workflowId,
                        nodeId,
                        metric)
                .orElseGet(
                        () -> MetricBaseline.create(
                                org,
                                workflowId,
                                nodeId,
                                metric));
    }

    private Map<String, List<NodeExecutionMetric>> byNode(
            List<NodeExecutionMetric> rows) {

        Map<String, List<NodeExecutionMetric>> byNode =
                new LinkedHashMap<>();

        for (NodeExecutionMetric m : rows) {
            byNode.computeIfAbsent(
                    m.getNodeId(),
                    k -> new ArrayList<>())
                    .add(m);
        }

        return byNode;
    }

    private static final class FieldAgg {
        int present;
        int nullCount;
        final Map<String, Integer> typeCounts =
                new LinkedHashMap<>();
    }
}