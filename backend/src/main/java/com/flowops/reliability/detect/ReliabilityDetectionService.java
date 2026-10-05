package com.flowops.reliability.detect;

import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the per-execution detectors against a freshly telemetry-captured run (reliability
 * pivot, M3). Pure detectors here: it loads the run's telemetry + the relevant learned
 * baselines, asks each detector for findings, and hands them to the recorder.
 *
 * <p>Hybrid timing per the design: latency/output/behavioral fire right after the run
 * reaches a terminal state (the worker thread in {@code ExecutionEngine}); volume is
 * windowed and fires in the scheduled sweep instead (see {@code ReliabilityScheduler}).
 */
@Service
public class ReliabilityDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityDetectionService.class);

    private final NodeExecutionMetricRepository metrics;
    private final MetricBaselineRepository baselines;
    private final LatencyDetector latencyDetector;
    private final OutputDetector outputDetector;
    private final BehavioralDetector behavioralDetector;
    private final BusinessOutcomeDetector businessOutcomeDetector;
    private final AnomalyRecorder recorder;
    private final com.flowops.reliability.RecoveryVerificationService recoveryService;

    public ReliabilityDetectionService(
            NodeExecutionMetricRepository metrics,
            MetricBaselineRepository baselines,
            LatencyDetector latencyDetector,
            OutputDetector outputDetector,
            BehavioralDetector behavioralDetector,
            BusinessOutcomeDetector businessOutcomeDetector,
            AnomalyRecorder recorder,
            com.flowops.reliability.RecoveryVerificationService recoveryService) {
        this.metrics = metrics;
        this.baselines = baselines;
        this.latencyDetector = latencyDetector;
        this.outputDetector = outputDetector;
        this.behavioralDetector = behavioralDetector;
        this.businessOutcomeDetector = businessOutcomeDetector;
        this.recorder = recorder;
        this.recoveryService = recoveryService;
    }

    /**
     * Detects anomalies for one just-completed execution. Idempotent — the engine calls it
     * once per terminal transition, and the recorder swallows per-finding failures so this
     * can never break a run.
     */
    @Transactional
    public void detectInExecution(UUID executionId) {
        List<NodeExecutionMetric> rows = metrics.findByExecutionId(executionId);
        if (rows.isEmpty()) {
            return;
        }
        UUID organizationId = rows.get(0).getOrganizationId();
        UUID workflowId = rows.get(0).getWorkflowId();

        List<Finding> findings = new ArrayList<>();
        for (NodeExecutionMetric row : rows) {
            latencyDetector.detect(
                    baseline(workflowId, row.getNodeId(), BaseKey.LATENCY), row)
                    .ifPresent(findings::add);
            findings.addAll(outputDetector.detect(
                    baseline(workflowId, row.getNodeId(), BaseKey.OUTPUT_SCHEMA),
                    baseline(workflowId, row.getNodeId(), BaseKey.OUTPUT_SIZE),
                    row));
            findings.addAll(businessOutcomeDetector.detect(
                    baseline(workflowId, row.getNodeId(), BaseKey.OUTPUT_SIZE),
                    row));
        }
        findings.addAll(behavioralDetector.detect(
                baseline(workflowId, "", BaseKey.BEHAVIOR), rows));

        recorder.record(organizationId, workflowId, executionId, findings);
        if (!findings.isEmpty()) {
            log.info("Reliability detection for execution {} surfaced {} finding(s)",
                    executionId, findings.size());
        }

        // Also evaluate any active recovery verifications for this workflow
        recoveryService.evaluateExecution(workflowId, executionId);
    }

    private MetricBaseline baseline(UUID workflowId, String nodeId, String metric) {
        Optional<MetricBaseline> b = baselines.findByWorkflowIdAndNodeIdAndMetric(workflowId, nodeId, metric);
        return b.orElse(null);
    }

    private static final class BaseKey {
        static final String LATENCY = "LATENCY";
        static final String OUTPUT_SCHEMA = "OUTPUT_SCHEMA";
        static final String OUTPUT_SIZE = "OUTPUT_SIZE";
        static final String BEHAVIOR = "BEHAVIOR";
    }
}
