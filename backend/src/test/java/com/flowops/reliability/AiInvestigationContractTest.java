package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.ai.AiServiceClient;
import com.flowops.api.ReliabilityEnvelopes;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.common.security.SensitiveDataRedactor;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.MetricBaseline;
import com.flowops.domain.Role;
import com.flowops.domain.Workflow;
import com.flowops.repository.AnomalyRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.repository.MetricBaselineRepository;
import com.flowops.repository.NodeExecutionAttemptRepository;
import com.flowops.repository.NodeExecutionMetricRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
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
class AiInvestigationContractTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

    private static final FlowOpsPrincipal MEMBER = new FlowOpsPrincipal(
            USER_ID,
            MEMBERSHIP_ID,
            ORG,
            Role.MEMBER,
            "tester@flowops.internal");

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
    @Mock
    private WorkflowVersionRepository versions;
    @Mock
    private NodeExecutionAttemptRepository nodeAttempts;
    @Mock
    private RateLimiter rateLimiter;

    private ReliabilityService service;
    private ReliabilityController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(MEMBER, null, List.of()));

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

        service.clearInvestigationCache();

        controller = new ReliabilityController(service, rateLimiter, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. Secret & PII Redactor Tests
    // =========================================================================

    @Test
    void redactorSanitizesBearerTokensAndHeaders() {
        String text = "Authorization: Bearer secret-token-12345 with api_key=xyz987";
        String redacted = SensitiveDataRedactor.redact(text);
        assertThat(redacted).doesNotContain("secret-token-12345");
        assertThat(redacted).doesNotContain("xyz987");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void redactorSanitizesCreditCardsAndEmails() {
        String text = "User dhruv.patil@example.com paid with card 4111-2222-3333-4444 in run.";
        String redacted = SensitiveDataRedactor.redact(text);
        assertThat(redacted).doesNotContain("dhruv.patil@example.com");
        assertThat(redacted).doesNotContain("4111-2222-3333-4444");
        assertThat(redacted).contains("[REDACTED_EMAIL]");
        assertThat(redacted).contains("[REDACTED_CC]");
    }

    @Test
    void redactorSanitizesPrivateKeys() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        String redacted = SensitiveDataRedactor.redact(pem);
        assertThat(redacted).doesNotContain("MIIEowIBAAKCAQEA");
        assertThat(redacted).isEqualTo("[REDACTED_PRIVATE_KEY]");
    }

    @Test
    void redactorSanitizesMapsRecursively() {
        Map<String, Object> input = Map.of(
                "apiKey", "secret-value",
                "nested", Map.of(
                        "password", "p@ssword",
                        "safeText", "Normal text with bearer eyJhbGciOi... token"));

        Map<String, Object> sanitized = SensitiveDataRedactor.redactMap(input);
        assertThat(sanitized.get("apiKey")).isEqualTo("[REDACTED]");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("nested");
        assertThat(nested.get("password")).isEqualTo("[REDACTED]");
        assertThat(nested.get("safeText").toString()).doesNotContain("eyJhbGciOi");
    }

    // =========================================================================
    // 2. Evidence Context Enrichment & Outbound Redaction
    // =========================================================================

    @Test
    void evidencePackageEnrichesContextAndSanitizesOutboundPayload() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-1",
                EXECUTION_ID,
                AnomalyType.LATENCY,
                AnomalySeverity.HIGH,
                "durationMs",
                "150ms",
                "4500ms",
                5.5,
                0.9,
                Map.of("kind", "LATENCY_SPIKE", "secretKey", "super-secret-pw"),
                "LATENCY:node-1");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(workflows.findByIdAndOrganizationId(WORKFLOW_ID, ORG)).thenReturn(Optional.of(
                Workflow.create(ORG, USER_ID, "Checkout Workflow", "", null)));

        MetricBaseline baseline = MetricBaseline.create(ORG, WORKFLOW_ID, "node-1", "durationMs");
        when(baselines.findByOrganizationIdAndWorkflowIdAndNodeIdAndMetric(ORG, WORKFLOW_ID, "node-1", "durationMs"))
                .thenReturn(Optional.of(baseline));

        when(aiClient.investigateAnomaly(argThat(evidencePkg -> {
            // Must contain enriched fields
            assertThat(evidencePkg).containsKey("workflow");
            assertThat(evidencePkg).containsKey("platformType");
            assertThat(evidencePkg).containsKey("baseline");
            // Must have redacted the sensitive map key
            @SuppressWarnings("unchecked")
            Map<String, Object> rawEv = (Map<String, Object>) evidencePkg.get("evidence");
            assertThat(rawEv.get("secretKey")).isEqualTo("[REDACTED]");
            return true;
        }))).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                "Grounded investigation summary",
                List.of(new ReliabilityEnvelopes.LikelyCause("Slow upstream API", "external-dependency", 0.85, "None", List.of("baseline.median"))),
                List.of(),
                "Delayed workflow",
                List.of(),
                0.85,
                true,
                "gemini-1.5-pro",
                Instant.now(),
                List.of("baseline.median"),
                "Increase timeout",
                List.of("Verify latency drops"),
                false));

        ReliabilityEnvelopes.InvestigateResponse response = service.investigateAnomaly(null, anomalyId);
        assertThat(response.result().configured()).isTrue();
        assertThat(response.result().summary()).isEqualTo("Grounded investigation summary");
        assertThat(response.result().evidenceCitations()).contains("baseline.median");
        assertThat(response.result().suggestedFix()).isEqualTo("Increase timeout");
    }

    // =========================================================================
    // 3. Fallback Resiliency on Failure / Unconfigured
    // =========================================================================

    @Test
    void fallsBackToRuleBasedWhenAiServiceThrowsException() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-zero",
                EXECUTION_ID,
                AnomalyType.OUTPUT,
                AnomalySeverity.HIGH,
                "outputCount",
                ">= 10",
                "0",
                4.0,
                0.9,
                Map.of("kind", "ZERO_OUTPUT"),
                "OUTPUT:node-zero");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(aiClient.investigateAnomaly(any())).thenThrow(new ApiException(ErrorCode.AI_SERVICE_ERROR));

        // Must NOT throw ApiException — must return rule-based fallback gracefully
        ReliabilityEnvelopes.InvestigateResponse response = service.investigateAnomaly(null, anomalyId);

        assertThat(response.result()).isNotNull();
        assertThat(response.result().fallback()).isTrue();
        assertThat(response.result().configured()).isFalse();
        assertThat(response.result().model()).isEqualTo("rule-based-fallback");
        assertThat(response.result().summary()).contains("ZERO_OUTPUT");
        assertThat(response.result().likelyCauses()).isNotEmpty();
        assertThat(response.result().likelyCauses().get(0).citations()).contains("actual", "expected");
        assertThat(response.result().suggestedFix()).isNotBlank();
        assertThat(response.result().verificationChecklist()).isNotEmpty();

        // Must persist investigation in anomaly evidence
        verify(anomalies).save(argThat(saved -> {
            assertThat(saved.getEvidence()).containsKey("investigation");
            @SuppressWarnings("unchecked")
            Map<String, Object> inv = (Map<String, Object>) saved.getEvidence().get("investigation");
            assertThat(inv.get("fallback")).isEqualTo(true);
            return true;
        }));
    }

    @Test
    void fallsBackToRuleBasedWhenAiServiceReportsUnconfigured() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-drift",
                EXECUTION_ID,
                AnomalyType.OUTPUT,
                AnomalySeverity.HIGH,
                "outputSignature",
                "hash:abc",
                "hash:xyz",
                1.0,
                0.9,
                Map.of("kind", "SCHEMA_DRIFT"),
                "OUTPUT:node-drift");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(aiClient.investigateAnomaly(any())).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                null, List.of(), List.of(), null, List.of(), 0.0, false, null, Instant.now()));

        ReliabilityEnvelopes.InvestigateResponse response = service.investigateAnomaly(null, anomalyId);

        assertThat(response.result().fallback()).isTrue();
        assertThat(response.result().configured()).isFalse();
        assertThat(response.result().model()).isEqualTo("rule-based-fallback");
        assertThat(response.result().summary()).contains("SCHEMA_DRIFT");
    }

    // =========================================================================
    // 4. Investigation Caching & Invalidation
    // =========================================================================

    @Test
    void cachesInvestigationResultForSameAnomalyState() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-stall",
                EXECUTION_ID,
                AnomalyType.VOLUME,
                AnomalySeverity.CRITICAL,
                "intervalSeconds",
                "3600s",
                "86400s",
                24.0,
                0.95,
                Map.of("kind", "SILENT_STALL"),
                "VOLUME:node-stall");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(aiClient.investigateAnomaly(any())).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                "Stall analysis", List.of(), List.of(), "Paused workflow", List.of(), 0.9, true, "model-1", Instant.now(),
                List.of("intervalSeconds"), "Enable cron trigger", List.of("Verify trigger"), false));

        // First call invokes aiClient
        ReliabilityEnvelopes.InvestigateResponse resp1 = service.investigateAnomaly(null, anomalyId);
        assertThat(resp1.result().summary()).isEqualTo("Stall analysis");
        verify(aiClient, times(1)).investigateAnomaly(any());

        // Second call with same state MUST hit cache and not re-invoke aiClient
        ReliabilityEnvelopes.InvestigateResponse resp2 = service.investigateAnomaly(null, anomalyId);
        assertThat(resp2.result().summary()).isEqualTo("Stall analysis");
        verify(aiClient, times(1)).investigateAnomaly(any());
    }

    @Test
    void cacheInvalidatesWhenAnomalyStateChanges() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-err",
                EXECUTION_ID,
                AnomalyType.OUTPUT,
                AnomalySeverity.HIGH,
                "failureRate",
                "0%",
                "100%",
                1.0,
                0.9,
                Map.of("kind", "ERROR_SPIKE"),
                "OUTPUT:node-err");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(aiClient.investigateAnomaly(any())).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                "Error analysis 1", List.of(), List.of(), null, List.of(), 0.9, true, "model-1", Instant.now(),
                List.of(), "Fix bug", List.of(), false));

        service.investigateAnomaly(null, anomalyId);
        verify(aiClient, times(1)).investigateAnomaly(any());

        // Anomaly state changes (e.g. repeat occurrence increments affectedExecutions)
        anomaly.aggregate(AnomalySeverity.CRITICAL, "100%", 1.0, 0.9, UUID.randomUUID(), Map.of("kind", "ERROR_SPIKE"));

        when(aiClient.investigateAnomaly(any())).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                "Error analysis 2", List.of(), List.of(), null, List.of(), 0.9, true, "model-1", Instant.now(),
                List.of(), "Fix bug updated", List.of(), false));

        ReliabilityEnvelopes.InvestigateResponse resp2 = service.investigateAnomaly(null, anomalyId);
        assertThat(resp2.result().summary()).isEqualTo("Error analysis 2");
        // aiClient invoked second time due to state hash change
        verify(aiClient, times(2)).investigateAnomaly(any());
    }

    // =========================================================================
    // 5. Rate Limiting & Tenant Scoping
    // =========================================================================

    @Test
    void controllerEnforcesOrgLevelHourlyRateLimit() {
        UUID anomalyId = UUID.randomUUID();
        Anomaly anomaly = Anomaly.open(
                ORG,
                WORKFLOW_ID,
                "node-1",
                EXECUTION_ID,
                AnomalyType.LATENCY,
                AnomalySeverity.MEDIUM,
                "latency",
                "10ms",
                "50ms",
                2.0,
                0.8,
                Map.of(),
                "LATENCY:node-1");

        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.of(anomaly));
        when(aiClient.investigateAnomaly(any())).thenReturn(new ReliabilityEnvelopes.AIInvestigationResult(
                "Summary", List.of(), List.of(), null, List.of(), 0.8, true, "model-1", Instant.now()));

        controller.investigateAnomaly(null, anomalyId, null);

        // Verifies both user per-minute and org hourly rate limit are checked
        verify(rateLimiter).check(eq("ai:investigate:" + USER_ID), eq(20), eq(Duration.ofMinutes(1)));
        verify(rateLimiter).check(eq("ai:investigate:org:" + ORG), eq(20), eq(Duration.ofHours(1)));
    }

    @Test
    void rejectsInvestigationForCrossTenantAnomaly() {
        UUID anomalyId = UUID.randomUUID();
        when(anomalies.findByIdAndOrganizationId(anomalyId, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.investigateAnomaly(null, anomalyId))
                .isInstanceOf(ApiException.class)
                .hasMessage(ErrorCode.ANOMALY_NOT_FOUND.defaultMessage());

        verify(aiClient, never()).investigateAnomaly(any());
    }
}
