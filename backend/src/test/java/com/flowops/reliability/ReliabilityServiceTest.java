package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.ai.AiServiceClient;
import com.flowops.api.ReliabilityEnvelopes;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.Role;
import com.flowops.domain.Workflow;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ReliabilityServiceTest {

        private static final UUID ORG = UUID.randomUUID();
        private static final UUID OTHER_ORG = UUID.randomUUID();
        private static final UUID WORKFLOW = UUID.randomUUID();
        private static final UUID EXECUTION = UUID.randomUUID();
        private static final UUID USER_ID = UUID.randomUUID();
        private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

        private static final FlowOpsPrincipal MEMBER = new FlowOpsPrincipal(
                        USER_ID,
                        MEMBERSHIP_ID,
                        ORG,
                        Role.MEMBER,
                        "m@x.io");

        @Mock
        private com.flowops.repository.WorkflowVersionRepository versions;

        @Mock
        private com.flowops.repository.NodeExecutionAttemptRepository nodeAttempts;

        @Mock
        private AnomalyRepository anomalies;

        @Mock
        private NodeExecutionMetricRepository metrics;

        @Mock
        private MetricBaselineRepository baselines;

        @Mock
        private WorkflowRepository workflows;

        @Mock
        private IntegrationWorkflowRepository integrationWorkflows;

        @Mock
        private WorkflowExecutionRepository executions;

        @Mock
        private AiServiceClient aiClient;

        @Mock
        private RecoveryVerificationService recoveryService;

        private ReliabilityService service;

        @BeforeEach
        void setUp() {
                SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                                MEMBER,
                                                null,
                                                List.of()));

                service = new ReliabilityService(
                                anomalies,
                                metrics,
                                baselines,
                                workflows,
                                integrationWorkflows,
                                executions,
                                aiClient,
                                recoveryService,
                                versions,
                                nodeAttempts);
        }

        @AfterEach
        void tearDown() {
                SecurityContextHolder.clearContext();
        }

        // =========================================================================
        // Helpers
        // =========================================================================

        private Anomaly anomaly(
                        UUID id,
                        UUID workflowId,
                        String nodeId) {

                return Anomaly.open(
                                ORG,
                                workflowId,
                                nodeId,
                                EXECUTION,
                                AnomalyType.LATENCY,
                                AnomalySeverity.HIGH,
                                "LATENCY",
                                "p95 ≈ 1s",
                                "8s",
                                6.0,
                                0.9,
                                Map.of("kind", "latency-spike"),
                                "LATENCY:" + nodeId);
        }

        private Workflow workflow(UUID id) {
                return Workflow.create(
                                ORG,
                                USER_ID,
                                "wf",
                                "",
                                null);
        }

        // =========================================================================
        // Anomaly retrieval
        // =========================================================================

        @Test
        void getIsOrgScopedAndAntiEnumerates() {
                Anomaly a = anomaly(UUID.randomUUID(), WORKFLOW, "n1");
                UUID id = a.getId();

                when(
                                anomalies.findByIdAndOrganizationId(
                                                id,
                                                ORG))
                                .thenReturn(Optional.of(a));

                ReliabilityEnvelopes.AnomalyDetailResponse response = service.getAnomaly(null, id);

                assertThat(response.anomaly()).isNotNull();
                assertThat(response.anomaly().id()).isEqualTo(id);
                assertThat(response.anomaly().type())
                                .isEqualTo(AnomalyType.LATENCY);
                assertThat(response.anomaly().severity())
                                .isEqualTo(AnomalySeverity.HIGH);

                when(
                                anomalies.findByIdAndOrganizationId(
                                                id,
                                                ORG))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(
                                () -> service.getAnomaly(null, id))
                                .hasMessage(
                                                ErrorCode.ANOMALY_NOT_FOUND.defaultMessage());
        }

        @Test
        void otherOrgsAnomalyIsNotFound() {
                UUID id = UUID.randomUUID();

                when(
                                anomalies.findByIdAndOrganizationId(
                                                eq(id),
                                                eq(ORG)))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(
                                () -> service.getAnomaly(null, id))
                                .hasMessage(
                                                ErrorCode.ANOMALY_NOT_FOUND.defaultMessage());
        }

        // =========================================================================
        // Anomaly lifecycle
        // =========================================================================

        @Test
        void acknowledgePersistsTransition() {
                UUID id = UUID.randomUUID();

                Anomaly a = anomaly(id, WORKFLOW, "n1");

                when(
                                anomalies.findByIdAndOrganizationId(
                                                id,
                                                ORG))
                                .thenReturn(Optional.of(a));

                when(
                                anomalies.save(any(Anomaly.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                ReliabilityEnvelopes.AcknowledgeResponse response = service.acknowledge(
                                null,
                                id,
                                "acknowledged");

                assertThat(response.anomaly()).isNotNull();
                assertThat(response.anomaly().status())
                                .isEqualTo(AnomalyStatus.ACKNOWLEDGED);

                verify(anomalies).save(a);
        }

        @Test
        void resolvePersistsTransition() {
                UUID id = UUID.randomUUID();

                Anomaly a = anomaly(id, WORKFLOW, "n1");

                when(
                                anomalies.findByIdAndOrganizationId(
                                                id,
                                                ORG))
                                .thenReturn(Optional.of(a));

                when(
                                anomalies.save(any(Anomaly.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                ReliabilityEnvelopes.ResolveResponse response = service.resolve(
                                null,
                                id,
                                "fixed");

                assertThat(response.anomaly()).isNotNull();
                assertThat(response.anomaly().status())
                                .isEqualTo(AnomalyStatus.RESOLVED);

                verify(anomalies).save(a);
        }

        @Test
        void falsePositivePersistsTransition() {
                UUID id = UUID.randomUUID();

                Anomaly a = anomaly(id, WORKFLOW, "n1");

                when(
                                anomalies.findByIdAndOrganizationId(
                                                id,
                                                ORG))
                                .thenReturn(Optional.of(a));

                when(
                                anomalies.save(any(Anomaly.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                ReliabilityEnvelopes.FalsePositiveResponse response = service.markFalsePositive(
                                null,
                                id,
                                "expected behavior");

                assertThat(response.anomaly()).isNotNull();
                assertThat(response.anomaly().status())
                                .isEqualTo(AnomalyStatus.FALSE_POSITIVE);

                verify(anomalies).save(a);
        }

        // =========================================================================
        // Workflow health
        // =========================================================================

        @Test
        void healthRequiresWorkflowInOrg() {
                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(
                                () -> service.getWorkflowHealth(
                                                null,
                                                WORKFLOW))
                                .hasMessage(
                                                ErrorCode.WORKFLOW_NOT_FOUND.defaultMessage());
        }

        @Test
        void healthPenalizesFailuresAndOpenAnomalies() {

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(
                                                Optional.of(
                                                                workflow(WORKFLOW)));

                when(
                                executions.countByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(2L);

                when(
                                executions.countSucceededByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                when(
                                executions.countFailedByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                Anomaly openLatency = anomaly(
                                UUID.randomUUID(),
                                WORKFLOW,
                                "n1");

                when(
                                anomalies.findByOrganizationIdAndWorkflowIdAndStatus(
                                                ORG,
                                                WORKFLOW,
                                                AnomalyStatus.OPEN))
                                .thenReturn(
                                                List.of(openLatency));

                when(
                                executions.findTopByWorkflowIdAndOrganizationIdOrderByFinishedAtDesc(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(Optional.empty());

                ReliabilityEnvelopes.WorkflowHealthResponse response = service.getWorkflowHealth(
                                null,
                                WORKFLOW);

                ReliabilityEnvelopes.WorkflowHealth health = response.health();

                assertThat(health.totalExecutions())
                                .isEqualTo(2L);

                assertThat(health.succeededExecutions())
                                .isEqualTo(1L);

                assertThat(health.failedExecutions())
                                .isEqualTo(1L);

                assertThat(health.successRate())
                                .isEqualTo(0.5);

                assertThat(health.openAnomalies())
                                .isEqualTo(1L);

                assertThat(health.highAnomalies())
                                .isEqualTo(1L);

                assertThat(health.reliabilityScore())
                                .isEqualTo(75);

                assertThat(health.workflowId())
                                .isEqualTo(WORKFLOW);

                assertThat(health.workflowName())
                                .isEqualTo("wf");
        }

        @Test
        void healthIsPerfectWithoutProblems() {

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(
                                                Optional.of(
                                                                workflow(WORKFLOW)));

                when(
                                executions.countByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                when(
                                executions.countSucceededByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                when(
                                executions.countFailedByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(0L);

                when(
                                anomalies.findByOrganizationIdAndWorkflowIdAndStatus(
                                                ORG,
                                                WORKFLOW,
                                                AnomalyStatus.OPEN))
                                .thenReturn(List.of());

                when(
                                executions.findTopByWorkflowIdAndOrganizationIdOrderByFinishedAtDesc(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(Optional.empty());

                ReliabilityEnvelopes.WorkflowHealthResponse response = service.getWorkflowHealth(
                                null,
                                WORKFLOW);

                ReliabilityEnvelopes.WorkflowHealth health = response.health();

                assertThat(health.reliabilityScore())
                                .isEqualTo(100);

                assertThat(health.successRate())
                                .isEqualTo(1.0);

                assertThat(health.openAnomalies())
                                .isEqualTo(0);
        }

        @Test
        void resolvedAnomalyDoesNotCountAsOpen() {

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(
                                                Optional.of(
                                                                workflow(WORKFLOW)));

                when(
                                executions.countByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                when(
                                executions.countSucceededByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(1L);

                when(
                                executions.countFailedByOrganizationIdAndWorkflowId(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(0L);

                when(
                                anomalies.findByOrganizationIdAndWorkflowIdAndStatus(
                                                ORG,
                                                WORKFLOW,
                                                AnomalyStatus.OPEN))
                                .thenReturn(List.of());

                when(
                                executions.findTopByWorkflowIdAndOrganizationIdOrderByFinishedAtDesc(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(Optional.empty());

                ReliabilityEnvelopes.WorkflowHealthResponse response = service.getWorkflowHealth(
                                null,
                                WORKFLOW);

                assertThat(response.health().openAnomalies())
                                .isEqualTo(0);

                assertThat(response.health().reliabilityScore())
                                .isEqualTo(100);
        }

        // =========================================================================
        // Anomaly list
        // =========================================================================

        @Test
        void listAnomaliesReturnsOrgScopedResults() {

                Anomaly first = anomaly(
                                UUID.randomUUID(),
                                WORKFLOW,
                                "n1");

                Anomaly second = anomaly(
                                UUID.randomUUID(),
                                WORKFLOW,
                                "n2");

                when(
                                anomalies.findByOrganizationIdOrderByDetectedAtDesc(
                                                ORG))
                                .thenReturn(
                                                List.of(first, second));

                ReliabilityEnvelopes.AnomaliesResponse response = service.listAnomalies(
                                null,
                                Optional.empty(),
                                Optional.empty());

                assertThat(response.anomalies())
                                .hasSize(2);

                assertThat(response.anomalies())
                                .extracting(
                                                ReliabilityEnvelopes.AnomalySummary::type)
                                .containsOnly(
                                                AnomalyType.LATENCY);
        }

        @Test
        void listAnomaliesCanFilterByStatus() {

                Anomaly a = anomaly(
                                UUID.randomUUID(),
                                WORKFLOW,
                                "n1");

                when(
                                anomalies.findByOrganizationIdAndStatusOrderByDetectedAtDesc(
                                                ORG,
                                                AnomalyStatus.OPEN))
                                .thenReturn(
                                                List.of(a));

                ReliabilityEnvelopes.AnomaliesResponse response = service.listAnomalies(
                                null,
                                Optional.of(AnomalyStatus.OPEN),
                                Optional.empty());

                assertThat(response.anomalies())
                                .hasSize(1);

                assertThat(response.anomalies().get(0).status())
                                .isEqualTo(AnomalyStatus.OPEN);
        }

        @Test
        void listAnomaliesCanFilterByWorkflow() {

                Anomaly a = anomaly(
                                UUID.randomUUID(),
                                WORKFLOW,
                                "n1");

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(
                                                Optional.of(
                                                                workflow(WORKFLOW)));

                when(
                                anomalies.findByOrganizationIdAndWorkflowIdOrderByDetectedAtDesc(
                                                ORG,
                                                WORKFLOW))
                                .thenReturn(
                                                List.of(a));

                ReliabilityEnvelopes.AnomaliesResponse response = service.listAnomalies(
                                null,
                                Optional.empty(),
                                Optional.of(WORKFLOW));

                assertThat(response.anomalies())
                                .hasSize(1);

                assertThat(response.anomalies().get(0).workflowId())
                                .isEqualTo(WORKFLOW);
        }

        // =========================================================================
        // Workflow metrics
        // =========================================================================

        @Test
        void workflowMetricsRequiresWorkflowInOrg() {

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(
                                () -> service.getWorkflowMetrics(
                                                null,
                                                WORKFLOW))
                                .hasMessage(
                                                ErrorCode.WORKFLOW_NOT_FOUND.defaultMessage());
        }

        @Test
        void workflowMetricsReturnsExpectedEnvelope() {

                when(
                                workflows.findByIdAndOrganizationId(
                                                WORKFLOW,
                                                ORG))
                                .thenReturn(
                                                Optional.of(
                                                                workflow(WORKFLOW)));

                when(
                                baselines.findByOrganizationIdAndWorkflowIdAndMetric(
                                                ORG,
                                                WORKFLOW,
                                                "LATENCY"))
                                .thenReturn(List.of());

                when(
                                baselines.findByOrganizationIdAndWorkflowIdAndMetric(
                                                ORG,
                                                WORKFLOW,
                                                "OUTPUT_SIZE"))
                                .thenReturn(List.of());

                when(
                                baselines.findByOrganizationIdAndWorkflowIdAndNodeIdAndMetric(
                                                ORG,
                                                WORKFLOW,
                                                "",
                                                "VOLUME"))
                                .thenReturn(Optional.empty());

                ReliabilityEnvelopes.WorkflowMetricsResponse response = service.getWorkflowMetrics(
                                null,
                                WORKFLOW);

                assertThat(response.metrics())
                                .isNotNull();

                assertThat(response.metrics().workflowId())
                                .isEqualTo(WORKFLOW);

                assertThat(response.metrics().latency())
                                .isEmpty();

                assertThat(response.metrics().volume())
                                .isEmpty();

                assertThat(response.metrics().successRate())
                                .isEmpty();

                assertThat(response.metrics().anomalyFrequency())
                                .isEmpty();

                assertThat(response.metrics().reliabilityScoreHistory())
                                .isEmpty();
        }
}