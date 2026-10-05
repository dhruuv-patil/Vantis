package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.audit.AuditService;
import com.flowops.config.ExecutionProperties;
import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.CircuitBreaker;
import com.flowops.domain.DeadLetterRecord;
import com.flowops.domain.IdempotencyRecord;
import com.flowops.domain.Incident;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.NodeExecutionMetric;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.WorkflowExecution;
import com.flowops.execution.DeadLetterService;
import com.flowops.execution.ExecutionRecoveryService;
import com.flowops.execution.ExecutionStore;
import com.flowops.execution.NodeIdempotencyService;
import com.flowops.execution.QueueService;
import com.flowops.execution.RetryPolicyClassifier;
import com.flowops.reliability.detect.BusinessOutcomeDetector;
import com.flowops.reliability.detect.Finding;
import com.flowops.repository.CircuitBreakerRepository;
import com.flowops.repository.DeadLetterRecordRepository;
import com.flowops.repository.IdempotencyRecordRepository;
import com.flowops.repository.IncidentRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReliabilityHardeningTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Heartbeat stale execution detection recovers orphaned RUNNING executions")
    void heartbeatStaleExecutionDetectionRecoversOrphanedRun() {
        WorkflowExecutionRepository executions = mock(WorkflowExecutionRepository.class);
        ExecutionStore store = mock(ExecutionStore.class);
        QueueService queue = mock(QueueService.class);
        AuditService audit = mock(AuditService.class);
        ExecutionProperties props = ExecutionProperties.defaults();

        UUID staleRunId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        WorkflowExecution staleExec = mock(WorkflowExecution.class);
        when(staleExec.getOrganizationId()).thenReturn(orgId);

        when(executions.findStaleExecutionIds(any(Instant.class))).thenReturn(List.of(staleRunId));
        when(store.resetStuckNodesForRecovery(staleRunId)).thenReturn(2);
        when(store.getExecution(staleRunId)).thenReturn(staleExec);

        ExecutionRecoveryService recoveryService =
                new ExecutionRecoveryService(executions, store, queue, audit, props);

        int recovered = recoveryService.sweepStaleExecutions();

        assertThat(recovered).isEqualTo(1);
        verify(store).resetStuckNodesForRecovery(staleRunId);
        verify(queue).enqueue(staleRunId);
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Non-retryable HTTP 4xx and fatal syntax errors are not blindly retried")
    void nonRetryableErrorClassification() {
        RetryPolicyClassifier classifier = new RetryPolicyClassifier();

        // 4xx client errors are deterministic and must fail fast without wasting retry budget
        assertThat(classifier.isRetryable("HTTP 400 Bad Request", null)).isFalse();
        assertThat(classifier.isRetryable("401 Unauthorized token expired", null)).isFalse();
        assertThat(classifier.isRetryable("403 Forbidden access denied", null)).isFalse();
        assertThat(classifier.isRetryable("404 Not Found entity missing", null)).isFalse();
        assertThat(classifier.isRetryable("Invalid syntax in expression", null)).isFalse();

        // 5xx and network errors are transient and retryable
        assertThat(classifier.isRetryable("HTTP 500 Internal Server Error", null)).isTrue();
        assertThat(classifier.isRetryable("502 Bad Gateway upstream unavailable", null)).isTrue();
        assertThat(classifier.isRetryable("503 Service Unavailable", null)).isTrue();
        assertThat(classifier.isRetryable("504 Gateway Timeout", null)).isTrue();
        assertThat(classifier.isRetryable("Connection timed out after 5000ms", null)).isTrue();
        assertThat(classifier.isRetryable("Connection reset by peer", null)).isTrue();
    }

    @Test
    @DisplayName("Circuit breaker trips CLOSED -> OPEN on consecutive failures and blocks cascading calls")
    void circuitBreakerTripsAndProtectsExternalService() {
        CircuitBreakerRepository repo = mock(CircuitBreakerRepository.class);
        CircuitBreakerService breakerService = new CircuitBreakerService(repo);

        UUID orgId = UUID.randomUUID();
        String target = "api.stripe.com";

        CircuitBreaker cb = CircuitBreaker.create(orgId, target, 5, 30_000L);
        when(repo.findByOrganizationIdAndTargetDependency(orgId, target)).thenReturn(Optional.of(cb));

        // Initial state is CLOSED
        assertThat(breakerService.allowRequest(orgId, target)).isTrue();

        // Record consecutive failures up to threshold (5)
        for (int i = 0; i < 5; i++) {
            breakerService.recordFailure(orgId, target);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.STATE_OPEN);

        // While OPEN, further execution is blocked fast to prevent cascading system exhaustion
        assertThat(breakerService.allowRequest(orgId, target)).isFalse();

        // After timeout elapses, transitions to HALF_OPEN probe
        cb.setLastStateChangeAt(Instant.now().minus(java.time.Duration.ofSeconds(35)));
        assertThat(breakerService.allowRequest(orgId, target)).isTrue();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);

        // 2 consecutive successes in HALF_OPEN reset breaker to CLOSED
        breakerService.recordSuccess(orgId, target);
        breakerService.recordSuccess(orgId, target);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
        assertThat(cb.getFailureCount()).isZero();
    }

    @Test
    @DisplayName("Dead letter queue captures unrecoverable failed executions with input and context")
    void deadLetterCapturesTerminalFailures() {
        DeadLetterRecordRepository dlqRepo = mock(DeadLetterRecordRepository.class);
        DeadLetterService dlqService = new DeadLetterService(dlqRepo);

        UUID orgId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getId()).thenReturn(executionId);
        when(execution.getOrganizationId()).thenReturn(orgId);
        when(execution.getWorkflowId()).thenReturn(workflowId);
        when(execution.getWorkflowVersionId()).thenReturn(versionId);

        ObjectNode triggerPayload = mapper.createObjectNode().put("amount", 5000);
        when(execution.getTriggerPayload()).thenReturn(triggerPayload);

        ArgumentCaptor<DeadLetterRecord> captor = ArgumentCaptor.forClass(DeadLetterRecord.class);

        ObjectNode context = mapper.createObjectNode().put("worker", "worker-1");
        dlqService.capture(
                execution,
                "node-charge",
                "stripe_payment",
                3,
                "Payment declined: insufficient funds",
                context);

        verify(dlqRepo).save(captor.capture());
        DeadLetterRecord record = captor.getValue();

        assertThat(record.getOrganizationId()).isEqualTo(orgId);
        assertThat(record.getWorkflowId()).isEqualTo(workflowId);
        assertThat(record.getExecutionId()).isEqualTo(executionId);
        assertThat(record.getFailedNodeId()).isEqualTo("node-charge");
        assertThat(record.getAttempts()).isEqualTo(3);
        assertThat(record.getError()).contains("Payment declined");
        assertThat(record.getOriginalInput().get("amount").asInt()).isEqualTo(5000);
    }

    @Test
    @DisplayName("Business outcome anomaly detector catches HTTP 200 with suspiciously empty payload")
    void businessOutcomeAnomalyDetectsSuspiciousEmptyOutput() {
        ReliabilityProperties props = ReliabilityProperties.defaults();
        BusinessOutcomeDetector detector = new BusinessOutcomeDetector(props);

        MetricBaseline baseline = mock(MetricBaseline.class);
        when(baseline.getSampleCount()).thenReturn(50);
        when(baseline.getMedian()).thenReturn(1200.0);
        when(baseline.getMad()).thenReturn(150.0);

        NodeExecutionMetric obs = mock(NodeExecutionMetric.class);
        when(obs.getStatus()).thenReturn(NodeRunStatus.SUCCEEDED);
        when(obs.getHttpStatus()).thenReturn(200);
        when(obs.getNodeId()).thenReturn("sync-users");
        when(obs.getOutputSignature()).thenReturn(Map.of("form", "EMPTY", "arrayLength", 0));
        when(obs.getOutputSize()).thenReturn(2);

        List<Finding> findings = detector.detect(baseline, obs);
        assertThat(findings).isNotEmpty();
        Finding finding = findings.get(0);
        assertThat(finding.metric()).isEqualTo("BUSINESS_OUTCOME");
        assertThat(finding.actualValue()).contains("zero records returned");

        // Normal output with non-empty payload returns no findings
        NodeExecutionMetric healthyObs = mock(NodeExecutionMetric.class);
        when(healthyObs.getStatus()).thenReturn(NodeRunStatus.SUCCEEDED);
        when(healthyObs.getHttpStatus()).thenReturn(200);
        when(healthyObs.getNodeId()).thenReturn("sync-users");
        when(healthyObs.getOutputSignature()).thenReturn(Map.of("form", "LIST", "arrayLength", 50));
        when(healthyObs.getOutputSize()).thenReturn(1150);

        List<Finding> healthyFindings = detector.detect(baseline, healthyObs);
        assertThat(healthyFindings).isEmpty();
    }

    @Test
    @DisplayName("Dependency intelligence correlates multi-workflow failures into a single incident")
    void dependencyIntelligenceCorrelatesSharedFailures() {
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        DependencyIntelligenceService depService = new DependencyIntelligenceService(incidentRepo);

        UUID orgId = UUID.randomUUID();
        UUID workflowA = UUID.randomUUID();
        UUID workflowB = UUID.randomUUID();
        UUID execA = UUID.randomUUID();
        UUID execB = UUID.randomUUID();

        // 1st workflow fails on Stripe API
        depService.recordFailure(orgId, workflowA, execA, "Stripe API", "504 Gateway Timeout");
        verify(incidentRepo, times(0)).save(any(Incident.class));

        // 2nd workflow also fails on Stripe API within window -> correlated incident created!
        depService.recordFailure(orgId, workflowB, execB, "Stripe API", "Connection timed out");

        ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepo, times(1)).save(incidentCaptor.capture());

        Incident created = incidentCaptor.getValue();
        assertThat(created.getTitle()).isEqualTo("Likely shared dependency incident: Stripe API");
        assertThat(created.getDescription()).contains("failures across 2 workflows caused by Stripe API");
    }

    @Test
    @DisplayName("Node idempotency caching prevents duplicate execution of side-effects")
    void nodeIdempotencyPreventsDuplicateSideEffects() {
        IdempotencyRecordRepository repo = mock(IdempotencyRecordRepository.class);
        NodeIdempotencyService idempotencyService = new NodeIdempotencyService(repo);

        UUID orgId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        String idempotencyKey = "stripe-charge-tx-998877";

        // Initial check returns empty (first execution)
        when(repo.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey))
                .thenReturn(Optional.empty());

        assertThat(idempotencyService.findCompleted(orgId, idempotencyKey)).isEmpty();

        // Save completed effect
        IdempotencyRecord record = IdempotencyRecord.start(
                orgId,
                execId,
                "node-charge",
                idempotencyKey,
                1);
        ObjectNode responsePayload = mapper.createObjectNode()
                .put("chargeId", "ch_12345")
                .put("status", "succeeded");
        record.complete(responsePayload);

        when(repo.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey))
                .thenReturn(Optional.of(record));

        // Subsequent check returns cached completed result, preventing repeated side-effect
        var cached = idempotencyService.findCompleted(orgId, idempotencyKey);
        assertThat(cached).isPresent();
        assertThat(cached.get().getEffectStatus()).isEqualTo("COMPLETED");
        assertThat(cached.get().getResponsePayload().get("chargeId").asText()).isEqualTo("ch_12345");
    }
}
