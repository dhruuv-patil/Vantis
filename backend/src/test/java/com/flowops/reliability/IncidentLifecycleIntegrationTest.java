package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowops.audit.AuditService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.AuditAction;
import com.flowops.domain.AuditLog;
import com.flowops.domain.Incident;
import com.flowops.domain.IncidentStatus;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.Role;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import com.flowops.notification.NotificationService;
import com.flowops.reliability.baseline.RobustStats;
import com.flowops.reliability.detect.AnomalyRecorder;
import com.flowops.reliability.detect.Finding;
import com.flowops.reliability.detect.SeverityCalculator;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.AuditLogRepository;
import com.flowops.repository.IncidentRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class IncidentLifecycleIntegrationTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final FlowOpsPrincipal PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, UUID.randomUUID(), ORG_ID, Role.ADMIN, "admin@test.io");

    private InMemoryIncidentRepository incidentRepo;
    private InMemoryAnomalyRepository anomalyRepo;
    private InMemoryMetricRepository metricRepo;
    private InMemoryBaselineRepository baselineRepo;
    private AuditLogRepository auditRepo;
    private List<AuditLog> capturedAudits;

    private AuditService auditService;
    private ReliabilityProperties properties;
    private IncidentService incidentService;
    private RecoveryVerificationService recoveryService;
    private AnomalyRecorder anomalyRecorder;
    private SeverityCalculator severityCalculator;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of()));

        incidentRepo = new InMemoryIncidentRepository();
        anomalyRepo = new InMemoryAnomalyRepository();
        metricRepo = new InMemoryMetricRepository();
        baselineRepo = new InMemoryBaselineRepository();
        capturedAudits = new ArrayList<>();

        auditRepo = mock(AuditLogRepository.class);
        when(auditRepo.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog l = inv.getArgument(0);
            capturedAudits.add(l);
            return l;
        });
        when(auditRepo.search(eq(ORG_ID), any(), any())).thenAnswer(inv -> {
            AuditAction action = inv.getArgument(1);
            return capturedAudits.stream()
                    .filter(a -> a.getOrganizationId().equals(ORG_ID) && (action == null || a.getAction() == action))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        });

        auditService = new AuditService(auditRepo);
        properties = new ReliabilityProperties(
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                5,
                Duration.ofHours(1),
                2.5,
                3.0,
                1.5,
                Duration.ofMinutes(15), // cooldown
                0.15,
                0.2,                   // minConfidence
                Duration.ofMinutes(30)
        );

        incidentService = new IncidentService(incidentRepo, auditService, anomalyRepo, properties);
        recoveryService = new RecoveryVerificationService(anomalyRepo, metricRepo, baselineRepo, properties, incidentService);
        severityCalculator = new SeverityCalculator();

        NotificationService mockNotifications = mock(NotificationService.class);
        SlackAlertService mockSlack = mock(SlackAlertService.class);
        WebhookEventDispatcher mockWebhooks = mock(WebhookEventDispatcher.class);

        anomalyRecorder = new AnomalyRecorder(
                anomalyRepo,
                severityCalculator,
                properties,
                mockNotifications,
                mockSlack,
                mockWebhooks,
                incidentService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("1. Incident Creation and Deduplication")
    class IncidentCreationAndDedupTests {

        @Test
        @DisplayName("Actionable anomaly (HIGH severity) automatically opens an incident card with fingerprint")
        void highSeverityAnomalyOpensIncident() {
            UUID executionId = UUID.randomUUID();
            Finding finding = new Finding(
                    AnomalyType.OUTPUT,
                    "node-1",
                    "OUTPUT_SIZE",
                    "50 items",
                    "0 items",
                    6.0,
                    0.95,
                    Map.of("kind", "zero-output"),
                    "OUTPUT:node-1:zero_output"
            );

            anomalyRecorder.record(ORG_ID, WORKFLOW_ID, executionId, List.of(finding));

            List<Incident> openIncidents = incidentRepo.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID);
            assertThat(openIncidents).hasSize(1);

            Incident inc = openIncidents.get(0);
            assertThat(inc.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(inc.getSeverity()).isEqualTo(AnomalySeverity.HIGH);
            assertThat(inc.getFingerprint()).isEqualTo(WORKFLOW_ID + ":OUTPUT:node-1:zero_output");
            assertThat(inc.getTitle()).contains("OUTPUT").contains("node-1");
            assertThat(inc.getExecutionId()).isEqualTo(executionId);
            assertThat(inc.getMetadata()).containsEntry("affectedExecutions", 1);
        }

        @Test
        @DisplayName("Low severity anomaly does not open an incident")
        void lowSeverityAnomalyDoesNotOpenIncident() {
            UUID executionId = UUID.randomUUID();
            // Low deviation and low confidence yields LOW severity
            Finding finding = new Finding(
                    AnomalyType.LATENCY,
                    "node-1",
                    "LATENCY",
                    "100ms",
                    "120ms",
                    0.5,
                    0.25,
                    Map.of("kind", "minor-variance"),
                    "LATENCY:node-1"
            );

            anomalyRecorder.record(ORG_ID, WORKFLOW_ID, executionId, List.of(finding));

            assertThat(anomalyRepo.count()).isEqualTo(1);
            assertThat(incidentRepo.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("Repeated anomalies for the same failure group update single incident card (zero duplicates)")
        void repeatedAnomaliesDeduplicateToSingleCard() {
            UUID exec1 = UUID.randomUUID();
            Finding f1 = new Finding(
                    AnomalyType.OUTPUT,
                    "node-1",
                    "OUTPUT_SIZE",
                    "50 items",
                    "0 items",
                    5.0,
                    0.95,
                    Map.of("kind", "zero-output"),
                    "OUTPUT:node-1:zero_output"
            );

            anomalyRecorder.record(ORG_ID, WORKFLOW_ID, exec1, List.of(f1));
            assertThat(incidentRepo.count()).isEqualTo(1);

            UUID exec2 = UUID.randomUUID();
            anomalyRecorder.record(ORG_ID, WORKFLOW_ID, exec2, List.of(f1));

            // Still exactly 1 incident row (deduplicated)
            assertThat(incidentRepo.count()).isEqualTo(1);

            Incident inc = incidentRepo.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID).get(0);
            assertThat(inc.getExecutionId()).isEqualTo(exec2);
            assertThat(inc.getMetadata()).containsEntry("affectedExecutions", 2);
        }
    }

    @Nested
    @DisplayName("2. Incident State Transitions and Audit Logging")
    class IncidentStateTransitionsTests {

        @Test
        @DisplayName("Acknowledge transitions incident to ACKNOWLEDGED and writes audit trail")
        void acknowledgeTransitionsAndAudits() {
            Incident inc = Incident.open(
                    ORG_ID, WORKFLOW_ID, UUID.randomUUID(), UUID.randomUUID(),
                    "fp-1", "Test Incident", "Desc", AnomalySeverity.HIGH, Map.of()
            );
            incidentRepo.save(inc);

            incidentService.acknowledge(PRINCIPAL, inc.getId());

            Incident updated = incidentRepo.findById(inc.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(updated.getAcknowledgedAt()).isNotNull();

            List<AuditLog> logs = auditRepo.search(ORG_ID, AuditAction.INCIDENT_ACKNOWLEDGED, Pageable.unpaged());
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getTargetId()).isEqualTo(inc.getId().toString());
            assertThat(logs.get(0).getSummary()).contains("acknowledged");
        }
    }

    @Nested
    @DisplayName("3. Recovery Verification and Resolution Guards")
    class RecoveryVerificationAndResolveGuardTests {

        @Test
        @DisplayName("Manual resolve blocked (409 ANOMALY_ALREADY_CLOSED) when linked anomaly is not verified healthy")
        void manualResolveBlockedWhenRecoveryNotVerified() {
            UUID anomalyId = UUID.randomUUID();
            Anomaly anomaly = Anomaly.open(
                    ORG_ID, WORKFLOW_ID, "node-1", UUID.randomUUID(),
                    AnomalyType.OUTPUT, AnomalySeverity.HIGH,
                    "OUTPUT_SIZE", "50 items", "0 items",
                    5.0, 0.95, Map.of(), "OUTPUT:node-1:zero_output"
            );
            anomalyRepo.save(anomaly);

            Incident inc = Incident.open(
                    ORG_ID, WORKFLOW_ID, UUID.randomUUID(), anomaly.getId(),
                    "fp-1", "Output Failure", "Desc", AnomalySeverity.HIGH, Map.of()
            );
            incidentRepo.save(inc);

            // Attempt manual resolve before verification
            assertThatThrownBy(() -> incidentService.resolve(PRINCIPAL, inc.getId()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException ae = (ApiException) e;
                        assertThat(ae.code()).isEqualTo(ErrorCode.ANOMALY_ALREADY_CLOSED);
                        assertThat(ae.getMessage()).contains("recovery is verified by healthy executions");
                    });

            assertThat(inc.getStatus()).isEqualTo(IncidentStatus.OPEN);
        }

        @Test
        @DisplayName("Recovery verification completes after healthy executions and auto-resolves linked incident")
        void recoveryVerificationCompletesAndResolvesIncident() {
            Anomaly anomaly = Anomaly.open(
                    ORG_ID, WORKFLOW_ID, "node-1", UUID.randomUUID(),
                    AnomalyType.LATENCY, AnomalySeverity.HIGH,
                    "LATENCY", "100ms", "500ms",
                    5.0, 0.90, Map.of(), "LATENCY:node-1"
            );
            anomalyRepo.save(anomaly);

            Incident inc = Incident.open(
                    ORG_ID, WORKFLOW_ID, UUID.randomUUID(), anomaly.getId(),
                    "fp-latency", "Latency Spike", "Desc", AnomalySeverity.HIGH, Map.of()
            );
            incidentRepo.save(inc);

            // Setup baseline for LATENCY on node-1: median = 100ms, MAD = 10ms
            MetricBaseline base = MetricBaseline.create(ORG_ID, WORKFLOW_ID, "node-1", "LATENCY");
            base.setNumeric(RobustStats.of(new double[] { 90.0, 95.0, 100.0, 105.0, 110.0 }));
            base.setSampleCount(5);
            baselineRepo.save(base);

            // User starts verification with requiredCount = 2
            recoveryService.startVerification(null, anomaly.getId(), 2);
            assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);

            Instant t1 = Instant.now().plusSeconds(1);
            // Run 1: healthy latency (100ms)
            UUID exec1 = UUID.randomUUID();
            NodeExecutionMetric m1 = NodeExecutionMetric.create(ORG_ID, WORKFLOW_ID, 1, exec1, "node-1", "http", NodeRunStatus.SUCCEEDED);
            m1.recordTiming(t1, t1.plusMillis(100), 100L);
            metricRepo.add(m1);
            recoveryService.evaluateExecution(WORKFLOW_ID, exec1);

            assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.VERIFYING_RECOVERY);
            assertThat(inc.getStatus()).isEqualTo(IncidentStatus.OPEN);

            Instant t2 = Instant.now().plusSeconds(2);
            // Run 2: healthy latency (98ms) -> threshold reached!
            UUID exec2 = UUID.randomUUID();
            NodeExecutionMetric m2 = NodeExecutionMetric.create(ORG_ID, WORKFLOW_ID, 1, exec2, "node-1", "http", NodeRunStatus.SUCCEEDED);
            m2.recordTiming(t2, t2.plusMillis(98), 98L);
            metricRepo.add(m2);
            recoveryService.evaluateExecution(WORKFLOW_ID, exec2);

            // Anomaly auto-resolved
            assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);

            // Linked incident auto-resolved!
            Incident resolvedInc = incidentRepo.findById(inc.getId()).orElseThrow();
            assertThat(resolvedInc.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(resolvedInc.getResolvedAt()).isNotNull();

            // Audit trail recorded
            List<AuditLog> resolveAudits = auditRepo.search(ORG_ID, AuditAction.INCIDENT_RESOLVED, Pageable.unpaged());
            assertThat(resolveAudits).hasSize(1);
            assertThat(resolveAudits.get(0).getSummary()).contains("recovery verification");
        }

        @Test
        @DisplayName("Cooldown recurrence reopens a resolved incident when failure reappears within 15 minutes")
        void cooldownRecurrenceReopensResolvedIncident() {
            String fingerprint = WORKFLOW_ID + ":OUTPUT:node-1:zero_output";
            Incident inc = Incident.open(
                    ORG_ID, WORKFLOW_ID, UUID.randomUUID(), UUID.randomUUID(),
                    fingerprint, "Zero Output", "Desc", AnomalySeverity.HIGH,
                    new HashMap<>(Map.of("affectedExecutions", 3))
            );
            inc.resolve();
            incidentRepo.save(inc);
            assertThat(inc.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

            // Recurrence within cooldown window
            UUID newExec = UUID.randomUUID();
            UUID newAnomalyId = UUID.randomUUID();

            Incident reopened = incidentService.recordAnomalyIncident(
                    ORG_ID,
                    WORKFLOW_ID,
                    newExec,
                    newAnomalyId,
                    fingerprint,
                    "Zero Output Reappeared",
                    "Recurrence occurred",
                    AnomalySeverity.HIGH,
                    Map.of("kind", "zero-output")
            );

            assertThat(reopened.getId()).isEqualTo(inc.getId());
            assertThat(reopened.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(reopened.getExecutionId()).isEqualTo(newExec);
            assertThat(reopened.getAnomalyId()).isEqualTo(newAnomalyId);
            assertThat(reopened.getMetadata()).containsEntry("affectedExecutions", 4);
            assertThat(reopened.getMetadata()).containsKey("reopenedAt");

            // Verify audit record for reopening
            List<AuditLog> auditLogs = auditRepo.search(ORG_ID, AuditAction.INCIDENT_ACKNOWLEDGED, Pageable.unpaged());
            assertThat(auditLogs).anyMatch(log -> log.getSummary().contains("re-opened due to recurrence"));
        }
    }

    // =========================================================================
    // In-memory repositories for test isolation and speed
    // =========================================================================

    private static class InMemoryIncidentRepository implements IncidentRepository {
        private final Map<UUID, Incident> store = new HashMap<>();

        @Override
        public <S extends Incident> S save(S entity) {
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<Incident> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Incident> findByIdAndOrganizationId(UUID id, UUID organizationId) {
            Incident inc = store.get(id);
            if (inc != null && inc.getOrganizationId().equals(organizationId)) {
                return Optional.of(inc);
            }
            return Optional.empty();
        }

        @Override
        public List<Incident> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }

        @Override
        public List<Incident> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID organizationId, IncidentStatus status) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId) && i.getStatus() == status)
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }

        @Override
        public List<Incident> findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(UUID organizationId, UUID workflowId) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId) && i.getWorkflowId().equals(workflowId))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }

        @Override
        public List<Incident> findByOrganizationIdAndAnomalyId(UUID organizationId, UUID anomalyId) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId)
                            && i.getAnomalyId() != null
                            && i.getAnomalyId().equals(anomalyId))
                    .toList();
        }

        @Override
        public Optional<Incident> findFirstByOrganizationIdAndFingerprintAndStatusInOrderByCreatedAtDesc(
                UUID organizationId, String fingerprint, Collection<IncidentStatus> statuses) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId)
                            && fingerprint.equals(i.getFingerprint())
                            && statuses.contains(i.getStatus()))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .findFirst();
        }

        @Override
        public Optional<Incident> findFirstByOrganizationIdAndFingerprintOrderByCreatedAtDesc(
                UUID organizationId, String fingerprint) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId)
                            && fingerprint.equals(i.getFingerprint()))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .findFirst();
        }

        @Override
        public long countByOrganizationIdAndStatus(UUID organizationId, IncidentStatus status) {
            return store.values().stream()
                    .filter(i -> i.getOrganizationId().equals(organizationId) && i.getStatus() == status)
                    .count();
        }

        @Override public long count() { return store.size(); }
        @Override public List<Incident> findAll() { return List.copyOf(store.values()); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void delete(Incident entity) { store.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends Incident> entities) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public List<Incident> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public <S extends Incident> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public void flush() {}
        @Override public <S extends Incident> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends Incident> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<Incident> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public Incident getOne(UUID id) { return store.get(id); }
        @Override public Incident getById(UUID id) { return store.get(id); }
        @Override public Incident getReferenceById(UUID id) { return store.get(id); }
        @Override public <S extends Incident> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends Incident> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends Incident> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends Incident> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends Incident> long count(Example<S> example) { return 0; }
        @Override public <S extends Incident> boolean exists(Example<S> example) { return false; }
        @Override public <S extends Incident, R> R findBy(Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<Incident> findAll(Sort sort) { return List.of(); }
        @Override public Page<Incident> findAll(Pageable pageable) { return Page.empty(); }
    }

    private static class InMemoryAnomalyRepository implements AnomalyRepository {
        private final Map<UUID, Anomaly> store = new HashMap<>();

        @Override
        public <S extends Anomaly> S save(S entity) {
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<Anomaly> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
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
        public Optional<Anomaly> findFirstByWorkflowIdAndDedupKeyOrderByDetectedAtDesc(UUID workflowId, String dedupKey) {
            return store.values().stream()
                    .filter(a -> a.getWorkflowId().equals(workflowId) && dedupKey.equals(a.getDedupKey()))
                    .sorted((a, b) -> b.getDetectedAt().compareTo(a.getDetectedAt()))
                    .findFirst();
        }

        @Override
        public List<Anomaly> findByWorkflowIdAndStatus(UUID workflowId, AnomalyStatus status) {
            return store.values().stream()
                    .filter(a -> a.getWorkflowId().equals(workflowId) && a.getStatus() == status)
                    .toList();
        }

        @Override
        public List<Anomaly> findByOrganizationIdAndWorkflowIdAndStatus(UUID organizationId, UUID workflowId, AnomalyStatus status) {
            return store.values().stream()
                    .filter(a -> a.getOrganizationId().equals(organizationId)
                            && a.getWorkflowId().equals(workflowId)
                            && a.getStatus() == status)
                    .toList();
        }

        @Override public List<Anomaly> findByOrganizationIdOrderByDetectedAtDesc(UUID organizationId) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndStatusOrderByDetectedAtDesc(UUID organizationId, AnomalyStatus status) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndWorkflowIdOrderByDetectedAtDesc(UUID organizationId, UUID workflowId) { return List.of(); }
        @Override public List<Anomaly> findByOrganizationIdAndTypeOrderByDetectedAtDesc(UUID organizationId, AnomalyType type) { return List.of(); }
        @Override public long countByOrganizationIdAndStatus(UUID organizationId, AnomalyStatus status) { return 0; }
        @Override public <S extends Anomaly> List<S> saveAll(Iterable<S> entities) { return List.of(); }
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
        @Override public long countByWorkflowIdAndStatusAndStartedAtAfter(UUID workflowId, NodeRunStatus status, Instant after) { return 0; }
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
            String key = entity.getWorkflowId() + ":" + (entity.getNodeId() != null ? entity.getNodeId() : "") + ":" + entity.getMetric();
            store.put(key, entity);
            return entity;
        }

        @Override
        public Optional<MetricBaseline> findByWorkflowIdAndNodeIdAndMetric(UUID workflowId, String nodeId, String metric) {
            String key = workflowId + ":" + (nodeId != null ? nodeId : "") + ":" + metric;
            return Optional.ofNullable(store.get(key));
        }

        @Override public List<MetricBaseline> findByWorkflowId(UUID workflowId) { return List.of(); }
        @Override public List<MetricBaseline> findByMetric(String metric) { return List.of(); }
        @Override public List<MetricBaseline> findByWorkflowIdAndMetric(UUID workflowId, String metric) { return List.of(); }
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
