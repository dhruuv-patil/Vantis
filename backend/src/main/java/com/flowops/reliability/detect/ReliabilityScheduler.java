package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.MetricBaseline;
import com.flowops.reliability.baseline.BaselineEngine;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.flowops.domain.NodeExecutionMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReliabilityScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ReliabilityScheduler.class);

    private final BaselineEngine baselines;
    private final MetricBaselineRepository baselineRepo;
    private final NodeExecutionMetricRepository metrics;
    private final VolumeDetector volumeDetector;
    private final SilentStallDetector silentStallDetector;
    private final ErrorSpikeDetector errorSpikeDetector;
    private final AnomalyRecorder recorder;
    private final ReliabilityProperties properties;

    public ReliabilityScheduler(
            BaselineEngine baselines,
            MetricBaselineRepository baselineRepo,
            NodeExecutionMetricRepository metrics,
            VolumeDetector volumeDetector,
            SilentStallDetector silentStallDetector,
            ErrorSpikeDetector errorSpikeDetector,
            AnomalyRecorder recorder,
            ReliabilityProperties properties) {

        this.baselines = baselines;
        this.baselineRepo = baselineRepo;
        this.metrics = metrics;
        this.volumeDetector = volumeDetector;
        this.silentStallDetector = silentStallDetector;
        this.errorSpikeDetector = errorSpikeDetector;
        this.recorder = recorder;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${flowops.reliability.scheduler-delay:5m}",
            initialDelayString = "${flowops.reliability.scheduler-delay:5m}")
    public void sweep() {
        // 1. Relearn rolling baselines.
        baselines.recomputeAll();

        // 2. Compare current volume against learned baseline.
        scanVolume();

        // 3. Scan for silent stalls on scheduled/recurring workflows.
        scanSilentStalls();

        // 4. Scan for sudden error rate spikes.
        scanErrorSpikes();
    }

    private void scanVolume() {
        Instant windowStart =
                Instant.now().minus(properties.schedulerDelay());

        List<MetricBaseline> volumeBaselines;

        try {
            volumeBaselines =
                    baselineRepo.findByMetric(BaselineEngine.VOLUME);
        } catch (RuntimeException e) {
            log.warn(
                    "Volume scan could not load baselines: {}",
                    e.toString());
            return;
        }

        for (MetricBaseline b : volumeBaselines) {
            try {
                long current =
                        metrics.countDistinctExecutionsSince(
                                b.getWorkflowId(),
                                windowStart);

                Instant dropStart =
                        Instant.now().minus(
                                properties.volumeDropWindow());

                long recent =
                        metrics.countDistinctExecutionsSince(
                                b.getWorkflowId(),
                                dropStart);

                Optional<Finding> finding =
                        volumeDetector.detect(
                                b,
                                current,
                                recent);

                finding.ifPresent(f ->
                        recorder.record(
                                b.getOrganizationId(),
                                b.getWorkflowId(),
                                null,
                                List.of(f)));

            } catch (RuntimeException e) {
                log.warn(
                        "Volume scan failed for workflow {}: {}",
                        b.getWorkflowId(),
                        e.toString());
            }
        }
    }

    private void scanSilentStalls() {
        Instant windowStart = Instant.now().minus(properties.baselineWindow());
        List<UUID> workflowIds;
        try {
            workflowIds = metrics.findDistinctWorkflowIdsSince(windowStart);
        } catch (RuntimeException e) {
            log.warn("Silent stall scan could not find workflows: {}", e.toString());
            return;
        }

        Instant now = Instant.now();
        for (UUID workflowId : workflowIds) {
            try {
                List<NodeExecutionMetric> recent = metrics.findTop30ByWorkflowIdOrderByStartedAtDesc(workflowId);
                if (recent.isEmpty()) {
                    continue;
                }
                UUID orgId = recent.get(0).getOrganizationId();
                silentStallDetector.detect(workflowId, recent, now)
                        .ifPresent(f -> recorder.record(orgId, workflowId, null, List.of(f)));
            } catch (RuntimeException e) {
                log.warn("Silent stall scan failed for workflow {}: {}", workflowId, e.toString());
            }
        }
    }

    private void scanErrorSpikes() {
        List<MetricBaseline> errorBaselines;
        try {
            errorBaselines = baselineRepo.findByMetric(BaselineEngine.ERROR_RATE);
        } catch (RuntimeException e) {
            log.warn("Error spike scan could not load baselines: {}", e.toString());
            return;
        }

        Instant windowStart = Instant.now().minus(properties.volumeDropWindow());
        for (MetricBaseline b : errorBaselines) {
            try {
                List<NodeExecutionMetric> recent = metrics.findByWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(
                        b.getWorkflowId(), windowStart);
                errorSpikeDetector.detect(b, recent)
                        .ifPresent(f -> recorder.record(b.getOrganizationId(), b.getWorkflowId(), null, List.of(f)));
            } catch (RuntimeException e) {
                log.warn("Error spike scan failed for workflow {}: {}", b.getWorkflowId(), e.toString());
            }
        }
    }
}