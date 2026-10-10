package com.flowops.reliability.detect;

import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.reliability.baseline.BaselineEngine;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAnomalyEngineTest {

    private ReliabilityProperties props;
    private BusinessOutcomeDetector businessOutcomeDetector;
    private OutputDetector outputDetector;
    private LatencyDetector latencyDetector;
    private SilentStallDetector silentStallDetector;
    private ErrorSpikeDetector errorSpikeDetector;

    private final UUID orgId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new ReliabilityProperties(
                Duration.ofHours(24), // baselineWindow
                Duration.ofMinutes(5), // schedulerDelay
                30,                    // minSampleCount
                Duration.ofHours(1),   // warmupPeriod
                2.5,                   // sensitivity
                3.0,                   // volumeZThreshold
                1.5,                   // volumeRatioFloor
                Duration.ofMinutes(15),// cooldown
                0.15,                  // nullRateSpike
                0.2,                   // minConfidence
                Duration.ofMinutes(30) // volumeDropWindow
        );

        businessOutcomeDetector = new BusinessOutcomeDetector(props);
        outputDetector = new OutputDetector(props);
        latencyDetector = new LatencyDetector(props);
        silentStallDetector = new SilentStallDetector(props);
        errorSpikeDetector = new ErrorSpikeDetector(props);
    }

    @Nested
    @DisplayName("1. Zero-Output Detector Tests")
    class ZeroOutputDetectorTests {

        @Test
        @DisplayName("Triggers on technical success with 0 items when historical baseline median > 0")
        void triggersOnZeroItemsWhenBaselinePositive() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "n8n-node-1", 40, 50.0, 5.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "n8n-node-1", "http", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("hash", "empty");
            sig.put("itemCount", 0);
            sig.put("truncated", false);
            obs.recordSignature(sig);
            obs.recordHttpStatus(200);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).hasSize(1);
            Finding f = findings.get(0);
            assertThat(f.type()).isEqualTo(AnomalyType.OUTPUT);
            assertThat(f.nodeId()).isEqualTo("n8n-node-1");
            assertThat(f.evidence()).containsEntry("kind", "zero-output");
            assertThat(f.evidence()).containsEntry("actualItems", 0);
            assertThat(f.evidence()).containsEntry("medianItems", 50.0);
            assertThat(f.dedupKey()).isEqualTo("OUTPUT:n8n-node-1:zero_output");
        }

        @Test
        @DisplayName("Does NOT trigger when baseline median is legitimately 0 items")
        void doesNotTriggerWhenBaselineIsZeroItems() {
            MetricBaseline baseline = DetectorTestData.numericBaseline("OUTPUT_SIZE", "filter-node", 40, 0.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "filter-node", "filter", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("hash", "empty");
            sig.put("itemCount", 0);
            obs.recordSignature(sig);
            obs.recordHttpStatus(200);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger when item count is uninstrumented or pruned (null)")
        void doesNotTriggerWhenItemCountNull() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "n8n-node-1", 40, 50.0, 5.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "n8n-node-1", "http", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("hash", "pruned");
            // No itemCount present in sig
            obs.recordSignature(sig);
            obs.recordHttpStatus(200);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger when baseline is warming up (sample count < minSampleCount)")
        void doesNotTriggerDuringColdStart() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "n8n-node-1", 5, 50.0, 5.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "n8n-node-1", "http", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = Map.of("itemCount", 0);
            obs.recordSignature(sig);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).isEmpty();
        }
    }

    @Nested
    @DisplayName("2. Output-Collapse Detector Tests")
    class OutputCollapseDetectorTests {

        @Test
        @DisplayName("Triggers on technical success when output items collapse by >80% on high-median node")
        void triggersOnSignificantItemCollapse() {
            // Baseline median 100 items, sample count 50
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "fetch-records", 50, 100.0, 2.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "fetch-records", "database", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = Map.of("itemCount", 5); // 95% drop
            obs.recordSignature(sig);
            obs.recordSizes(10, 5);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).hasSize(1);
            Finding f = findings.get(0);
            assertThat(f.type()).isEqualTo(AnomalyType.OUTPUT);
            assertThat(f.nodeId()).isEqualTo("fetch-records");
            assertThat(f.evidence()).containsEntry("kind", "output-collapse");
            assertThat(f.evidence()).containsEntry("medianItems", 100.0);
            assertThat(f.evidence()).containsEntry("actualItems", 5);
            assertThat(f.dedupKey()).isEqualTo("OUTPUT:fetch-records:output_collapse");
        }

        @Test
        @DisplayName("Does NOT trigger on normal variations (e.g. 100 -> 90 items)")
        void doesNotTriggerOnNormalVariation() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "fetch-records", 50, 100.0, 15.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "fetch-records", "database", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = Map.of("itemCount", 90);
            obs.recordSignature(sig);
            obs.recordSizes(10, 90);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger on small-volume nodes with median < 5 items")
        void doesNotTriggerOnLowMedianNodes() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("OUTPUT_SIZE", "fetch-one", 50, 3.0, 0.5);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "fetch-one", "database", NodeRunStatus.SUCCEEDED);
            Map<String, Object> sig = Map.of("itemCount", 1);
            obs.recordSignature(sig);

            List<Finding> findings = businessOutcomeDetector.detect(baseline, obs);

            assertThat(findings).isEmpty();
        }
    }

    @Nested
    @DisplayName("3. Schema-Drift Detector Tests")
    class SchemaDriftDetectorTests {

        @Test
        @DisplayName("Detects missing expected field with high confidence and deviation")
        void detectsMissingField() {
            Map<String, Object> baselineFields = new LinkedHashMap<>();
            baselineFields.put("id", Map.of("type", "string", "null", false, "form", "SCALAR"));
            baselineFields.put("email", Map.of("type", "string", "null", false, "form", "SCALAR"));
            MetricBaseline baseline = DetectorTestData.schemaBaseline("user-service", baselineFields, 50);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "user-service", "api", NodeRunStatus.SUCCEEDED);
            Map<String, Object> actualShape = Map.of("id", "string"); // missing 'email'
            obs.recordSignature(Map.of("shape", actualShape));

            List<Finding> findings = outputDetector.detect(baseline, null, obs);

            assertThat(findings).hasSize(1);
            Finding f = findings.get(0);
            assertThat(f.evidence()).containsEntry("kind", "field-missing");
            assertThat(f.evidence()).containsEntry("field", "email");
            assertThat(f.deviation()).isEqualTo(5.0);
            assertThat(f.confidence()).isGreaterThanOrEqualTo(0.75);
        }

        @Test
        @DisplayName("Detects scalar type changes")
        void detectsScalarTypeChange() {
            Map<String, Object> baselineFields = new LinkedHashMap<>();
            baselineFields.put("id", Map.of("type", "string", "null", false, "form", "SCALAR"));
            MetricBaseline baseline = DetectorTestData.schemaBaseline("user-service", baselineFields, 50);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "user-service", "api", NodeRunStatus.SUCCEEDED);
            Map<String, Object> actualShape = Map.of("id", "number"); // changed from string to number
            obs.recordSignature(Map.of("shape", actualShape));

            List<Finding> findings = outputDetector.detect(baseline, null, obs);

            assertThat(findings).hasSize(1);
            Finding f = findings.get(0);
            assertThat(f.evidence()).containsEntry("kind", "scalar-type-change");
            assertThat(f.evidence()).containsEntry("expectedType", "string");
            assertThat(f.evidence()).containsEntry("actualType", "number");
        }

        @Test
        @DisplayName("Detects benign field additions with lower severity/deviation")
        void detectsUnexpectedFieldAddition() {
            Map<String, Object> baselineFields = new LinkedHashMap<>();
            baselineFields.put("id", Map.of("type", "string", "null", false, "form", "SCALAR"));
            MetricBaseline baseline = DetectorTestData.schemaBaseline("user-service", baselineFields, 50);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "user-service", "api", NodeRunStatus.SUCCEEDED);
            Map<String, Object> actualShape = Map.of("id", "string", "nickname", "string");
            obs.recordSignature(Map.of("shape", actualShape));

            List<Finding> findings = outputDetector.detect(baseline, null, obs);

            assertThat(findings).hasSize(1);
            Finding f = findings.get(0);
            assertThat(f.evidence()).containsEntry("kind", "field-unexpected");
            assertThat(f.evidence()).containsEntry("field", "nickname");
            assertThat(f.deviation()).isEqualTo(3.0); // lower than missing (5.0)
        }

        @Test
        @DisplayName("Does NOT trigger when schema matches baseline perfectly")
        void doesNotTriggerOnIdenticalSchema() {
            Map<String, Object> baselineFields = new LinkedHashMap<>();
            baselineFields.put("id", Map.of("type", "string", "null", false, "form", "SCALAR"));
            baselineFields.put("status", Map.of("type", "string", "null", false, "form", "SCALAR"));
            MetricBaseline baseline = DetectorTestData.schemaBaseline("user-service", baselineFields, 50);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "user-service", "api", NodeRunStatus.SUCCEEDED);
            Map<String, Object> actualShape = Map.of("id", "string", "status", "string");
            obs.recordSignature(Map.of("shape", actualShape));

            List<Finding> findings = outputDetector.detect(baseline, null, obs);

            assertThat(findings).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. Latency-Spike Detector Tests")
    class LatencySpikeDetectorTests {

        @Test
        @DisplayName("Triggers on durationMs exceeding median + sensitivity * MAD-sigma")
        void triggersOnLatencySpike() {
            // Median 200ms, spread 20ms -> sigma ~ 20ms, threshold = 200 + 2.5 * 20 = 250ms
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("LATENCY", "api-call", 50, 200.0, 20.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "api-call", "http", NodeRunStatus.SUCCEEDED);
            obs.recordTiming(Instant.now().minusSeconds(1), Instant.now(), 600L); // 600ms > 250ms

            Optional<Finding> finding = latencyDetector.detect(baseline, obs);

            assertThat(finding).isPresent();
            Finding f = finding.get();
            assertThat(f.type()).isEqualTo(AnomalyType.LATENCY);
            assertThat(f.evidence()).containsEntry("kind", "latency-spike");
            assertThat(f.evidence()).containsEntry("actualMs", 600.0);
            assertThat(f.deviation()).isGreaterThan(3.0);
        }

        @Test
        @DisplayName("Does NOT trigger on normal latency within threshold")
        void doesNotTriggerOnNormalLatency() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("LATENCY", "api-call", 50, 200.0, 20.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "api-call", "http", NodeRunStatus.SUCCEEDED);
            obs.recordTiming(Instant.now().minusSeconds(1), Instant.now(), 210L);

            Optional<Finding> finding = latencyDetector.detect(baseline, obs);

            assertThat(finding).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger on fast execution (lower than median is not an anomaly)")
        void doesNotTriggerOnFasterExecution() {
            MetricBaseline baseline = DetectorTestData.numericBaselineWithMedian("LATENCY", "api-call", 50, 200.0, 20.0);

            NodeExecutionMetric obs = NodeExecutionMetric.create(orgId, workflowId, 1, executionId, "api-call", "http", NodeRunStatus.SUCCEEDED);
            obs.recordTiming(Instant.now().minusSeconds(1), Instant.now(), 50L);

            Optional<Finding> finding = latencyDetector.detect(baseline, obs);

            assertThat(finding).isEmpty();
        }
    }

    @Nested
    @DisplayName("5. Silent-Stall Detector Tests")
    class SilentStallDetectorTests {

        @Test
        @DisplayName("Triggers when workflow on regular 10-minute cadence stalls beyond grace period")
        void triggersOnSilentStall() {
            Instant baseTime = Instant.now().minus(Duration.ofMinutes(100));

            // Create 5 executions regularly spaced 10 minutes apart: t0, t0+10m, t0+20m, t0+30m, t0+40m
            List<NodeExecutionMetric> history = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID exec = UUID.randomUUID();
                NodeExecutionMetric m = NodeExecutionMetric.create(orgId, workflowId, 1, exec, "trigger", "schedule", NodeRunStatus.SUCCEEDED);
                m.recordTiming(baseTime.plus(Duration.ofMinutes(i * 10)), baseTime.plus(Duration.ofMinutes(i * 10)).plusSeconds(5), 5000L);
                history.add(m);
            }

            // Last run was at baseTime + 40m.
            // Now is baseTime + 100m -> 60 minutes elapsed since last run!
            // Expected max: 10m * 1.5 + 10m grace = 25m. 60m > 25m.
            Instant now = baseTime.plus(Duration.ofMinutes(100));

            Optional<Finding> finding = silentStallDetector.detect(workflowId, history, now);

            assertThat(finding).isPresent();
            Finding f = finding.get();
            assertThat(f.type()).isEqualTo(AnomalyType.VOLUME);
            assertThat(f.evidence()).containsEntry("kind", "silent-stall");
            assertThat(f.evidence()).containsEntry("medianIntervalMinutes", 10.0);
            assertThat(f.evidence()).containsEntry("elapsedMinutes", 60.0);
            assertThat(f.dedupKey()).isEqualTo("VOLUME:" + workflowId + ":silent_stall");
        }

        @Test
        @DisplayName("Does NOT trigger when workflow executed recently within expected window")
        void doesNotTriggerWhenRunningOnTime() {
            Instant baseTime = Instant.now().minus(Duration.ofMinutes(50));

            List<NodeExecutionMetric> history = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID exec = UUID.randomUUID();
                NodeExecutionMetric m = NodeExecutionMetric.create(orgId, workflowId, 1, exec, "trigger", "schedule", NodeRunStatus.SUCCEEDED);
                m.recordTiming(baseTime.plus(Duration.ofMinutes(i * 10)), baseTime.plus(Duration.ofMinutes(i * 10)).plusSeconds(5), 5000L);
                history.add(m);
            }

            // Last run was at baseTime + 40m. Now is baseTime + 48m (only 8m elapsed, < 10m).
            Instant now = baseTime.plus(Duration.ofMinutes(48));

            Optional<Finding> finding = silentStallDetector.detect(workflowId, history, now);

            assertThat(finding).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger on sporadic/unpredictable workflows with high variance")
        void doesNotTriggerOnIrregularExecutions() {
            Instant baseTime = Instant.now().minus(Duration.ofHours(20));

            List<NodeExecutionMetric> history = new ArrayList<>();
            // Intervals: 5m, 120m, 10m, 300m
            long[] offsets = {0, 5, 125, 135, 435};
            for (long offset : offsets) {
                UUID exec = UUID.randomUUID();
                NodeExecutionMetric m = NodeExecutionMetric.create(orgId, workflowId, 1, exec, "webhook", "webhook", NodeRunStatus.SUCCEEDED);
                m.recordTiming(baseTime.plus(Duration.ofMinutes(offset)), baseTime.plus(Duration.ofMinutes(offset)).plusSeconds(5), 5000L);
                history.add(m);
            }

            Instant now = baseTime.plus(Duration.ofMinutes(600));

            Optional<Finding> finding = silentStallDetector.detect(workflowId, history, now);

            assertThat(finding).isEmpty();
        }
    }

    @Nested
    @DisplayName("6. Error-Spike Detector Tests")
    class ErrorSpikeDetectorTests {

        @Test
        @DisplayName("Triggers when failure rate jumps from low baseline to high rate in recent window")
        void triggersOnErrorSpike() {
            // Learned baseline error rate 0.02 (2%), sampleCount 40
            MetricBaseline baseline = DetectorTestData.numericBaseline("ERROR_RATE", "", 40, 0.02);

            // Recent window: 10 executions, 6 failed (60% error rate)
            List<NodeExecutionMetric> recent = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                NodeRunStatus status = i < 6 ? NodeRunStatus.FAILED : NodeRunStatus.SUCCEEDED;
                recent.add(NodeExecutionMetric.create(orgId, workflowId, 1, UUID.randomUUID(), "node-1", "http", status));
            }

            Optional<Finding> finding = errorSpikeDetector.detect(baseline, recent);

            assertThat(finding).isPresent();
            Finding f = finding.get();
            assertThat(f.type()).isEqualTo(AnomalyType.BEHAVIORAL);
            assertThat(f.evidence()).containsEntry("kind", "error-spike");
            assertThat(f.evidence()).containsEntry("recentErrorRate", 0.6);
            assertThat(f.evidence()).containsEntry("recentFailedCount", 6L);
            assertThat(f.dedupKey()).isEqualTo("BEHAVIORAL:" + baseline.getWorkflowId() + ":error_spike");
        }

        @Test
        @DisplayName("Does NOT trigger when recent window has clean executions")
        void doesNotTriggerWhenRecentExecutionsSucceed() {
            MetricBaseline baseline = DetectorTestData.numericBaseline("ERROR_RATE", "", 40, 0.02);

            List<NodeExecutionMetric> recent = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                recent.add(NodeExecutionMetric.create(orgId, workflowId, 1, UUID.randomUUID(), "node-1", "http", NodeRunStatus.SUCCEEDED));
            }

            Optional<Finding> finding = errorSpikeDetector.detect(baseline, recent);

            assertThat(finding).isEmpty();
        }

        @Test
        @DisplayName("Does NOT trigger when baseline is warming up (sample count < minSampleCount)")
        void doesNotTriggerDuringColdStart() {
            MetricBaseline baseline = DetectorTestData.numericBaseline("ERROR_RATE", "", 5, 0.02);

            List<NodeExecutionMetric> recent = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                recent.add(NodeExecutionMetric.create(orgId, workflowId, 1, UUID.randomUUID(), "node-1", "http", NodeRunStatus.FAILED));
            }

            Optional<Finding> finding = errorSpikeDetector.detect(baseline, recent);

            assertThat(finding).isEmpty();
        }
    }

    @Nested
    @DisplayName("7. Idempotent Anomaly Recording Tests")
    class IdempotentAnomalyRecordingTests {

        @Test
        @DisplayName("Reprocessing the exact same execution does not create duplicate anomalies or bump affected executions")
        void reprocessingSameExecutionIsIdempotent() {
            AnomalyRepository repo = Mockito.mock(AnomalyRepository.class);
            SeverityCalculator calc = new SeverityCalculator();
            com.flowops.notification.NotificationService notif = Mockito.mock(com.flowops.notification.NotificationService.class);
            com.flowops.reliability.SlackAlertService slack = Mockito.mock(com.flowops.reliability.SlackAlertService.class);
            com.flowops.integration.delivery.WebhookEventDispatcher dispatcher = Mockito.mock(com.flowops.integration.delivery.WebhookEventDispatcher.class);

            AnomalyRecorder recorder = new AnomalyRecorder(repo, calc, props, notif, slack, dispatcher);

            Finding finding = new Finding(
                    AnomalyType.OUTPUT,
                    "node-1",
                    "OUTPUT_SIZE",
                    "expected 50 items",
                    "produced 0 items",
                    5.0,
                    0.90,
                    Map.of("kind", "zero-output"),
                    "OUTPUT:node-1:zero_output"
            );

            // First run: no existing anomaly in DB
            when(repo.findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(workflowId, "OUTPUT:node-1:zero_output"))
                    .thenReturn(Optional.empty());

            recorder.record(orgId, workflowId, executionId, List.of(finding));

            // Verify Anomaly was saved once
            verify(repo, times(1)).save(any(Anomaly.class));

            // Simulate the saved anomaly in DB with executionId
            Anomaly savedAnomaly = Anomaly.open(
                    orgId, workflowId, "node-1", executionId, AnomalyType.OUTPUT,
                    com.flowops.domain.AnomalySeverity.HIGH, "OUTPUT_SIZE", "expected 50 items", "produced 0 items",
                    5.0, 0.90, Map.of("kind", "zero-output"), "OUTPUT:node-1:zero_output"
            );

            // Second run: re-processing the exact same executionId
            when(repo.findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(workflowId, "OUTPUT:node-1:zero_output"))
                    .thenReturn(Optional.of(savedAnomaly));

            recorder.record(orgId, workflowId, executionId, List.of(finding));

            // Verify save was NOT called again (idempotent skip)
            verify(repo, times(1)).save(any(Anomaly.class));
            assertThat(savedAnomaly.getAffectedExecutions()).isEqualTo(1);
        }

        @Test
        @DisplayName("Different executionId for same problem within cooldown aggregates cleanly")
        void differentExecutionAggregatesCorrectly() {
            AnomalyRepository repo = Mockito.mock(AnomalyRepository.class);
            SeverityCalculator calc = new SeverityCalculator();
            com.flowops.notification.NotificationService notif = Mockito.mock(com.flowops.notification.NotificationService.class);
            com.flowops.reliability.SlackAlertService slack = Mockito.mock(com.flowops.reliability.SlackAlertService.class);
            com.flowops.integration.delivery.WebhookEventDispatcher dispatcher = Mockito.mock(com.flowops.integration.delivery.WebhookEventDispatcher.class);

            AnomalyRecorder recorder = new AnomalyRecorder(repo, calc, props, notif, slack, dispatcher);

            Finding finding = new Finding(
                    AnomalyType.OUTPUT,
                    "node-1",
                    "OUTPUT_SIZE",
                    "expected 50 items",
                    "produced 0 items",
                    5.0,
                    0.90,
                    Map.of("kind", "zero-output"),
                    "OUTPUT:node-1:zero_output"
            );

            Anomaly existing = Anomaly.open(
                    orgId, workflowId, "node-1", executionId, AnomalyType.OUTPUT,
                    com.flowops.domain.AnomalySeverity.HIGH, "OUTPUT_SIZE", "expected 50 items", "produced 0 items",
                    5.0, 0.90, Map.of("kind", "zero-output"), "OUTPUT:node-1:zero_output"
            );

            when(repo.findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(workflowId, "OUTPUT:node-1:zero_output"))
                    .thenReturn(Optional.of(existing));

            UUID secondExecutionId = UUID.randomUUID();
            recorder.record(orgId, workflowId, secondExecutionId, List.of(finding));

            // Aggregated: affectedExecutions bumped to 2
            verify(repo, times(1)).save(existing);
            assertThat(existing.getAffectedExecutions()).isEqualTo(2);
            assertThat(existing.getExecutionId()).isEqualTo(secondExecutionId);
        }
    }

    @Nested
    @DisplayName("8. Baseline Engine Outlier Exclusion Tests")
    class BaselineEngineOutlierExclusionTests {

        @Test
        @DisplayName("Excludes open anomalous executions so outliers do not corrupt learned baselines")
        void excludesOpenAnomalousExecutions() {
            NodeExecutionMetricRepository metricRepo = Mockito.mock(NodeExecutionMetricRepository.class);
            MetricBaselineRepository baselineRepo = Mockito.mock(MetricBaselineRepository.class);
            AnomalyRepository anomalyRepo = Mockito.mock(AnomalyRepository.class);

            BaselineEngine engine = new BaselineEngine(metricRepo, baselineRepo, props, anomalyRepo);

            UUID openAnomalyExecId = UUID.randomUUID();
            Anomaly openAnomaly = Anomaly.open(
                    orgId, workflowId, "node-1", openAnomalyExecId, AnomalyType.LATENCY,
                    com.flowops.domain.AnomalySeverity.CRITICAL, "LATENCY", "200ms", "50000ms",
                    20.0, 0.99, Map.of(), "LATENCY:node-1"
            );

            when(anomalyRepo.findByWorkflowIdAndStatus(workflowId, AnomalyStatus.OPEN))
                    .thenReturn(List.of(openAnomaly));

            // Setup 30 normal rows with 200ms latency, plus 1 anomalous row with 50000ms latency
            List<NodeExecutionMetric> rows = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                NodeExecutionMetric m = NodeExecutionMetric.create(orgId, workflowId, 1, UUID.randomUUID(), "node-1", "http", NodeRunStatus.SUCCEEDED);
                m.recordTiming(Instant.now().minusSeconds(100), Instant.now(), 200L);
                rows.add(m);
            }
            NodeExecutionMetric anomalousMetric = NodeExecutionMetric.create(orgId, workflowId, 1, openAnomalyExecId, "node-1", "http", NodeRunStatus.SUCCEEDED);
            anomalousMetric.recordTiming(Instant.now().minusSeconds(100), Instant.now(), 50000L);
            rows.add(anomalousMetric);

            when(metricRepo.findDistinctWorkflowIdsSince(any(Instant.class)))
                    .thenReturn(List.of(workflowId));
            when(metricRepo.findByWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(eq(workflowId), any(Instant.class)))
                    .thenReturn(rows);
            when(baselineRepo.findByWorkflowIdAndNodeIdAndMetric(any(), any(), any()))
                    .thenReturn(Optional.empty());

            engine.recomputeAll();

            // Verify saved baseline has median 200.0 (not inflated by the 50000ms anomaly)
            org.mockito.ArgumentCaptor<MetricBaseline> captor = org.mockito.ArgumentCaptor.forClass(MetricBaseline.class);
            verify(baselineRepo, Mockito.atLeastOnce()).save(captor.capture());

            MetricBaseline savedLatency = captor.getAllValues().stream()
                    .filter(b -> "LATENCY".equals(b.getMetric()))
                    .findFirst()
                    .orElseThrow();

            assertThat(savedLatency.getMedian()).isEqualTo(200.0);
            assertThat(savedLatency.getSampleCount()).isEqualTo(30); // 31 - 1 excluded
        }
    }
}
