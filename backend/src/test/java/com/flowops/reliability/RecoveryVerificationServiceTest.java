package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowops.common.error.ErrorCode;
import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.Role;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Pure Java unit test for {@link RecoveryVerificationService} without Mockito dynamic proxies
 * so it executes reliably inside sandboxed environments.
 */
class RecoveryVerificationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final UUID OTHER_WORKFLOW = UUID.randomUUID();
    private static final UUID EXECUTION_1 = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final FlowOpsPrincipal PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, UUID.randomUUID(), ORG, Role.MEMBER, "user@test.io");

    private InMemoryAnomalyRepository anomalies;
    private InMemoryMetricRepository metrics;
    private InMemoryBaselineRepository baselines;

    private ReliabilityProperties properties;
    private RecoveryVerificationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of()));

        properties = new ReliabilityProperties(
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                5,
                Duration.ofHours(1),
                2.5,
                3.0,
                1.5,
                Duration.ofMinutes(15),
                0.15,
                0.2,
                Duration.ofMinutes(30));

        anomalies = new InMemoryAnomalyRepository();
        metrics = new InMemoryMetricRepository();
        baselines = new InMemoryBaselineRepository();

        service = new RecoveryVerificationService(anomalies, metrics, baselines, properties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Anomaly createAnomaly(String metric, String nodeId) {
        Anomaly a = Anomaly.open(
                ORG, WORKFLOW, nodeId, EXECUTION_1,
                AnomalyType.LATENCY, AnomalySeverity.HIGH,
                metric, "100ms", "500ms",
                5.0, 0.9, Map.of("kind", "latency-spike"),
                "LATENCY:" + nodeId);
        anomalies.save(a);
        return a;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void startVerificationTransitionsStatusToVerifyingRecovery() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");

        Anomaly result = service.startVerification(null, anomaly.getId(), 5);

        assertThat(result.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);
        assertThat(result.getRecoveryStartedAt()).isNotNull();
        assertThat(result.getRecoveryHealthyCount()).isZero();
        assertThat(result.getRecoveryRequiredCount()).isEqualTo(5);
    }

    @Test
    void statusEndpointReturnsWaitingForDataSnapshotWhenNoExecutions() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");
        service.startVerification(null, anomaly.getId(), 5);

        RecoveryVerificationService.RecoveryStatus snapshot =
                service.getRecoveryStatus(null, anomaly.getId());

        assertThat(snapshot.verificationActive()).isTrue();
        assertThat(snapshot.healthyCount()).isZero();
        assertThat(snapshot.observedCount()).isZero();
        assertThat(snapshot.requiredCount()).isEqualTo(5);
    }

    @Test
    void healthyExecutionsAdvanceCounterAndAutoResolveAtThreshold() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");
        service.startVerification(null, anomaly.getId(), 3); // requires 3 healthy runs

        // Baseline: median = 100ms, MAD = 10ms -> robustSigma ~14.8ms -> threshold ~137ms
        MetricBaseline base = MetricBaseline.create(ORG, WORKFLOW, "node-1", "LATENCY");
        base.setNumeric(com.flowops.reliability.baseline.RobustStats.of(
                new double[]{90, 95, 100, 105, 110}));
        base.setSampleCount(5);
        baselines.save(base);

        Instant now = Instant.now();

        // Execution 1: healthy (110ms <= threshold)
        UUID exec1 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec1, "node-1", 110L, now));
        service.evaluateExecution(WORKFLOW, exec1);
        assertThat(anomaly.getRecoveryHealthyCount()).isEqualTo(1);
        assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);

        // Execution 2: healthy (105ms)
        UUID exec2 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec2, "node-1", 105L, now.plusSeconds(1)));
        service.evaluateExecution(WORKFLOW, exec2);
        assertThat(anomaly.getRecoveryHealthyCount()).isEqualTo(2);
        assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);

        // Execution 3: healthy (98ms) -> reaches requiredCount (3) -> RESOLVED!
        UUID exec3 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec3, "node-1", 98L, now.plusSeconds(2)));
        service.evaluateExecution(WORKFLOW, exec3);

        assertThat(anomaly.getRecoveryHealthyCount()).isEqualTo(3);
        assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
    }

    @Test
    void anomalousExecutionResetsHealthyStreakToZero() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");
        service.startVerification(null, anomaly.getId(), 5);

        MetricBaseline base = MetricBaseline.create(ORG, WORKFLOW, "node-1", "LATENCY");
        base.setNumeric(com.flowops.reliability.baseline.RobustStats.of(
                new double[]{90, 95, 100, 105, 110}));
        base.setSampleCount(5);
        baselines.save(base);

        Instant now = Instant.now();

        // 2 healthy runs
        UUID exec1 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec1, "node-1", 100L, now));
        service.evaluateExecution(WORKFLOW, exec1);

        UUID exec2 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec2, "node-1", 105L, now.plusSeconds(1)));
        service.evaluateExecution(WORKFLOW, exec2);

        assertThat(anomaly.getRecoveryHealthyCount()).isEqualTo(2);

        // 3rd run is ANOMALOUS (500ms > threshold)
        UUID exec3 = UUID.randomUUID();
        metrics.add(createNodeMetric(exec3, "node-1", 500L, now.plusSeconds(2)));
        service.evaluateExecution(WORKFLOW, exec3);

        // Healthy count resets to 0; observed count increases to 3
        assertThat(anomaly.getRecoveryHealthyCount()).isZero();
        assertThat(anomaly.getRecoveryObservedCount()).isEqualTo(3);
        assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);
    }

    @Test
    void executionsFromAnotherWorkflowAreIgnored() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");
        service.startVerification(null, anomaly.getId(), 5);

        UUID otherExec = UUID.randomUUID();
        metrics.add(createNodeMetric(otherExec, "node-1", 100L, Instant.now()));
        service.evaluateExecution(OTHER_WORKFLOW, otherExec);

        assertThat(anomaly.getRecoveryHealthyCount()).isZero();
        assertThat(anomaly.getRecoveryObservedCount()).isZero();
    }

    @Test
    void duplicateExecutionEvaluationIsCountedOnlyOnce() {
        Anomaly anomaly = createAnomaly("LATENCY", "node-1");
        service.startVerification(null, anomaly.getId(), 5);

        MetricBaseline base = MetricBaseline.create(ORG, WORKFLOW, "node-1", "LATENCY");
        base.setNumeric(com.flowops.reliability.baseline.RobustStats.of(
                new double[]{90, 95, 100, 105, 110}));
        base.setSampleCount(5);
        baselines.save(base);

        UUID exec = UUID.randomUUID();
        metrics.add(createNodeMetric(exec, "node-1", 100L, Instant.now()));

        service.evaluateExecution(WORKFLOW, exec);
        service.evaluateExecution(WORKFLOW, exec);

        assertThat(anomaly.getRecoveryHealthyCount()).isEqualTo(1);
        assertThat(anomaly.getRecoveryObservedCount()).isEqualTo(1);
    }

    @Test
    void cannotStartVerificationOnAnotherOrgsAnomaly() {
        Anomaly otherOrgAnomaly = Anomaly.open(
                OTHER_ORG, WORKFLOW, "node-1", EXECUTION_1,
                AnomalyType.LATENCY, AnomalySeverity.HIGH,
                "LATENCY", "100ms", "500ms", 5.0, 0.9, Map.of(), "key");
        anomalies.save(otherOrgAnomaly);

        assertThatThrownBy(() -> service.startVerification(null, otherOrgAnomaly.getId(), 5))
                .hasMessage(ErrorCode.ANOMALY_NOT_FOUND.defaultMessage());
    }

    private NodeExecutionMetric createNodeMetric(UUID executionId, String nodeId, Long durationMs, Instant startedAt) {
        NodeExecutionMetric m = NodeExecutionMetric.create(
                ORG, WORKFLOW, 1, executionId, nodeId, "HTTP", NodeRunStatus.SUCCEEDED);
        m.recordTiming(startedAt, startedAt.plusMillis(durationMs != null ? durationMs : 10), durationMs);
        return m;
    }

    // =========================================================================
    // In-memory test repositories
    // =========================================================================

    private static class InMemoryAnomalyRepository implements AnomalyRepository {
        private final Map<UUID, Anomaly> store = new HashMap<>();

        @Override
        public <S extends Anomaly> S save(S entity) {
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<Anomaly> findByIdAndOrganizationId(UUID id, UUID organizationId) {
            Anomaly a = store.get(id);
            if (a != null && a.getOrganizationId().equals(organizationId)) {
                return Optional.of(a);
            }
            return Optional.empty();
        }

        @Override
        public List<Anomaly> findByWorkflowIdAndStatus(UUID workflowId, AnomalyStatus status) {
            return store.values().stream()
                    .filter(a -> a.getWorkflowId().equals(workflowId) && a.getStatus() == status)
                    .toList();
        }

        @Override
        public Optional<Anomaly> findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(UUID workflowId, String dedupKey) {
            return store.values().stream()
                    .filter(a -> a.getWorkflowId().equals(workflowId) && a.getDedupKey().equals(dedupKey))
                    .findFirst();
        }

        @Override public List<Anomaly> findByOrganizationIdOrderByDetectedAtDesc(UUID organizationId) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndStatusOrderByDetectedAtDesc(UUID organizationId, AnomalyStatus status) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndWorkflowIdOrderByDetectedAtDesc(UUID organizationId, UUID workflowId) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndTypeOrderByDetectedAtDesc(UUID organizationId, AnomalyType type) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndWorkflowIdAndStatus(UUID organizationId, UUID workflowId, AnomalyStatus status) { return List.of(); }
        @Override public long countByOrganizationIdAndStatus(UUID organizationId, AnomalyStatus status) { return 0; }
        @Override public <S extends Anomaly> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public Optional<Anomaly> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public List<Anomaly> findAll() { return List.copyOf(store.values()); }
        @Override public List<Anomaly> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void delete(Anomaly entity) { store.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends Anomaly> entities) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public void flush() {}
        @Override public <S extends Anomaly> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends Anomaly> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<Anomaly> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public Anomaly getOne(UUID id) { return store.get(id); }
        @Override public Anomaly getById(UUID id) { return store.get(id); }
        @Override public Anomaly getReferenceById(UUID id) { return store.get(id); }
        @Override public <S extends Anomaly> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends Anomaly> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends Anomaly> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends Anomaly> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends Anomaly> long count(Example<S> example) { return 0; }
        @Override public <S extends Anomaly> boolean exists(Example<S> example) { return false; }
        @Override public <S extends Anomaly, R> R findBy(Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<Anomaly> findAll(Sort sort) { return List.of(); }
        @Override public Page<Anomaly> findAll(Pageable pageable) { return Page.empty(); }
    }

    private static class InMemoryMetricRepository implements NodeExecutionMetricRepository {
        private final List<NodeExecutionMetric> metricsList = new ArrayList<>();

        public void add(NodeExecutionMetric m) {
            metricsList.add(m);
        }

        @Override
        public List<NodeExecutionMetric> findByExecutionId(UUID executionId) {
            return metricsList.stream()
                    .filter(m -> m.getExecutionId().equals(executionId))
                    .toList();
        }

        @Override public Optional<NodeExecutionMetric> findByExecutionIdAndNodeId(UUID executionId, String nodeId) { return Optional.empty(); }
        @Override public void deleteByExecutionId(UUID executionId) {}
        @Override public List<NodeExecutionMetric> findByOrganizationIdAndWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(UUID organizationId, UUID workflowId, Instant after) { return List.of(); }
        @Override public List<NodeExecutionMetric> findByWorkflowIdAndNodeIdAndStartedAtAfterOrderByStartedAtAsc(UUID workflowId, String nodeId, Instant after) { return List.of(); }
        @Override public List<NodeExecutionMetric> findByWorkflowIdAndStartedAtAfterOrderByStartedAtAsc(UUID workflowId, Instant after) { return List.of(); }
        @Override public Optional<NodeExecutionMetric> findByExecutionIdAndNodeIdAndOrganizationId(UUID executionId, String nodeId, UUID organizationId) { return Optional.empty(); }
        @Override public List<UUID> findDistinctWorkflowIdsSince(Instant after) { return List.of(); }
        @Override public long countDistinctExecutionsSince(UUID workflowId, Instant after) { return 0; }
        @Override public List<NodeExecutionMetric> findTop30ByWorkflowIdOrderByStartedAtDesc(UUID workflowId) { return List.of(); }
        @Override public long countByWorkflowIdAndStatusAndStartedAtAfter(UUID workflowId, com.flowops.domain.NodeRunStatus status, Instant after) { return 0; }
        @Override public <S extends NodeExecutionMetric> S save(S entity) { metricsList.add(entity); return entity; }
        @Override public <S extends NodeExecutionMetric> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public Optional<NodeExecutionMetric> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public List<NodeExecutionMetric> findAll() { return List.copyOf(metricsList); }
        @Override public List<NodeExecutionMetric> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return metricsList.size(); }
        @Override public void deleteById(UUID id) {}
        @Override public void delete(NodeExecutionMetric entity) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends NodeExecutionMetric> entities) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends NodeExecutionMetric> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends NodeExecutionMetric> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<NodeExecutionMetric> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public NodeExecutionMetric getOne(UUID id) { return null; }
        @Override public NodeExecutionMetric getById(UUID id) { return null; }
        @Override public NodeExecutionMetric getReferenceById(UUID id) { return null; }
        @Override public <S extends NodeExecutionMetric> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends NodeExecutionMetric> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends NodeExecutionMetric> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends NodeExecutionMetric> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends NodeExecutionMetric> long count(Example<S> example) { return 0; }
        @Override public <S extends NodeExecutionMetric> boolean exists(Example<S> example) { return false; }
        @Override public <S extends NodeExecutionMetric, R> R findBy(Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<NodeExecutionMetric> findAll(Sort sort) { return List.of(); }
        @Override public Page<NodeExecutionMetric> findAll(Pageable pageable) { return Page.empty(); }
    }

    private static class InMemoryBaselineRepository implements MetricBaselineRepository {
        private final Map<String, MetricBaseline> store = new HashMap<>();

        @Override
        public <S extends MetricBaseline> S save(S entity) {
            String key = entity.getWorkflowId() + ":" + entity.getNodeId() + ":" + entity.getMetric();
            store.put(key, entity);
            return entity;
        }

        @Override
        public Optional<MetricBaseline> findByWorkflowIdAndNodeIdAndMetric(UUID workflowId, String nodeId, String metric) {
            String key = workflowId + ":" + (nodeId != null ? nodeId : "") + ":" + metric;
            return Optional.ofNullable(store.get(key));
        }

        @Override public List<MetricBaseline> findByWorkflowId(UUID workflowId) {
            return store.values().stream().filter(b -> b.getWorkflowId().equals(workflowId)).toList();
        }
        @Override public List<MetricBaseline> findByMetric(String metric) {
            return store.values().stream().filter(b -> b.getMetric().equals(metric)).toList();
        }
        @Override public List<MetricBaseline> findByWorkflowIdAndMetric(UUID workflowId, String metric) {
            return findByWorkflowId(workflowId).stream().filter(b -> b.getMetric().equals(metric)).toList();
        }
        @Override public List<MetricBaseline> findByOrganizationIdAndWorkflowIdAndMetric(UUID organizationId, UUID workflowId, String metric) { return List.of(); }
        @Override public Optional<MetricBaseline> findByOrganizationIdAndWorkflowIdAndNodeIdAndMetric(UUID organizationId, UUID workflowId, String nodeId, String metric) { return Optional.empty(); }
        @Override public <S extends MetricBaseline> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public Optional<MetricBaseline> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public List<MetricBaseline> findAll() { return List.copyOf(store.values()); }
        @Override public List<MetricBaseline> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) {}
        @Override public void delete(MetricBaseline entity) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends MetricBaseline> entities) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends MetricBaseline> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends MetricBaseline> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<MetricBaseline> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public MetricBaseline getOne(UUID id) { return null; }
        @Override public MetricBaseline getById(UUID id) { return null; }
        @Override public MetricBaseline getReferenceById(UUID id) { return null; }
        @Override public <S extends MetricBaseline> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends MetricBaseline> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends MetricBaseline> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends MetricBaseline> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends MetricBaseline> long count(Example<S> example) { return 0; }
        @Override public <S extends MetricBaseline> boolean exists(Example<S> example) { return false; }
        @Override public <S extends MetricBaseline, R> R findBy(Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<MetricBaseline> findAll(Sort sort) { return List.of(); }
        @Override public Page<MetricBaseline> findAll(Pageable pageable) { return Page.empty(); }
    }
}
