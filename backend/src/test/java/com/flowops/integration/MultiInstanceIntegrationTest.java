package com.flowops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.api.IntegrationResponse;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.CryptoProperties;
import com.flowops.domain.ExecutionEvent;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.IntegrationSyncState;
import com.flowops.domain.Role;
import com.flowops.execution.ExecutionEventService;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.WorkflowProviderRegistry;
import com.flowops.repository.ExecutionEventRepository;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.IntegrationSyncStateRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests multi-instance support for workflow automation providers:
 * - Connecting multiple instances of the same provider type (e.g., Prod n8n and Staging n8n)
 * - Unique connection naming per provider type
 * - Soft-disable and re-enable lifecycle
 * - Tenant isolation across organizations
 * - Independent idempotent execution event ingestion without cross-instance collisions
 */
class MultiInstanceIntegrationTest {

    private IntegrationRepository integrationRepo;
    private IntegrationCredentialRepository credentialRepo;
    private IntegrationSyncStateRepository syncStateRepo;
    private CredentialCipher cipher;
    private com.flowops.audit.AuditService audit;
    private WorkflowProviderRegistry providerRegistry;
    private IntegrationService service;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        integrationRepo = mock(IntegrationRepository.class);
        credentialRepo = mock(IntegrationCredentialRepository.class);
        syncStateRepo = mock(IntegrationSyncStateRepository.class);
        audit = mock(com.flowops.audit.AuditService.class);
        providerRegistry = mock(WorkflowProviderRegistry.class);

        byte[] raw = new byte[CryptoProperties.KEY_BYTES];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 1);
        }
        cipher = new CredentialCipher(new CryptoProperties(Base64.getEncoder().encodeToString(raw)));

        service = new IntegrationService(
                integrationRepo,
                credentialRepo,
                cipher,
                audit,
                providerRegistry,
                null, // entitlementService
                syncStateRepo,
                null  // circuitBreaker
        );
    }

    private FlowOpsPrincipal admin(UUID orgId) {
        return new FlowOpsPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                orgId,
                Role.ADMIN,
                "admin@example.com");
    }

    @Test
    void allowsConnectingMultipleInstancesOfSameProvider() {
        FlowOpsPrincipal principal = admin(orgA);

        when(integrationRepo.findByOrganizationIdAndTypeAndName(orgA, "n8n", "Production n8n"))
                .thenReturn(Optional.empty());
        when(integrationRepo.findByOrganizationIdAndTypeAndName(orgA, "n8n", "Staging n8n"))
                .thenReturn(Optional.empty());

        Map<String, String> prodConfig = Map.of("baseUrl", "https://prod-n8n.internal", "apiKey", "prod-key-1234");
        Map<String, String> stagingConfig = Map.of("baseUrl", "https://staging-n8n.internal", "apiKey", "stag-key-5678");

        Integration prod = service.connectProvider(
                principal,
                IntegrationType.N8N,
                "Production n8n",
                cipher.encryptJson(prodConfig),
                "1234",
                prodConfig,
                Set.of("apiKey"));

        Integration staging = service.connectProvider(
                principal,
                IntegrationType.N8N,
                "Staging n8n",
                cipher.encryptJson(stagingConfig),
                "5678",
                stagingConfig,
                Set.of("apiKey"));

        assertThat(prod).isNotNull();
        assertThat(staging).isNotNull();
        assertThat(prod.getName()).isEqualTo("Production n8n");
        assertThat(staging.getName()).isEqualTo("Staging n8n");
        assertThat(prod.getType()).isEqualTo("n8n");
        assertThat(staging.getType()).isEqualTo("n8n");
        assertThat(prod.getId()).isNotEqualTo(staging.getId());

        verify(integrationRepo).save(prod);
        verify(integrationRepo).save(staging);
    }

    @Test
    void reconnectingSameInstanceNameUpdatesExistingRow() {
        FlowOpsPrincipal principal = admin(orgA);
        UUID existingId = UUID.randomUUID();

        Integration existing = Integration.createConnected(
                orgA, principal.userId(), "n8n", "Production n8n", "1234");

        when(integrationRepo.findByOrganizationIdAndTypeAndName(orgA, "n8n", "Production n8n"))
                .thenReturn(Optional.of(existing));

        IntegrationCredential existingCred = IntegrationCredential.create(
                existing.getId(), cipher.encryptJson(Map.of("apiKey", "old-key")));
        when(credentialRepo.findByIntegrationId(existing.getId()))
                .thenReturn(Optional.of(existingCred));

        Map<String, String> newConfig = Map.of("baseUrl", "https://prod-n8n.internal", "apiKey", "new-key-9999");

        Integration updated = service.connectProvider(
                principal,
                IntegrationType.N8N,
                "Production n8n",
                cipher.encryptJson(newConfig),
                "9999",
                newConfig,
                Set.of("apiKey"));

        assertThat(updated.getName()).isEqualTo("Production n8n");
        assertThat(updated.getHint()).isEqualTo("9999");
        assertThat(updated.getId()).isEqualTo(existing.getId());
    }

    @Test
    void softDisableAndReEnableLifecycle() {
        FlowOpsPrincipal principal = admin(orgA);
        Integration integration = Integration.createConnected(
                orgA, principal.userId(), "n8n", "Production n8n", "1234");

        when(integrationRepo.findByIdAndOrganizationId(integration.getId(), orgA))
                .thenReturn(Optional.of(integration));

        // Soft-disable
        IntegrationResponse disabledResponse = service.disable(principal, integration.getId());
        assertThat(disabledResponse.disabled()).isTrue();
        assertThat(disabledResponse.disabledAt()).isNotNull();
        assertThat(integration.isDisabled()).isTrue();

        // Re-enable
        IntegrationResponse enabledResponse = service.enable(principal, integration.getId());
        assertThat(enabledResponse.disabled()).isFalse();
        assertThat(enabledResponse.disabledAt()).isNull();
        assertThat(integration.isDisabled()).isFalse();
    }

    @Test
    void tenantIsolationPreventsAccessToOtherOrganizationIntegrations() {
        FlowOpsPrincipal principalA = admin(orgA);
        FlowOpsPrincipal principalB = admin(orgB);

        UUID integrationId = UUID.randomUUID();
        when(integrationRepo.findByIdAndOrganizationId(integrationId, orgB))
                .thenReturn(Optional.empty());

        // Org B cannot view, disable, or enable Org A's integration
        assertThatThrownBy(() -> service.get(principalB, integrationId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTEGRATION_NOT_FOUND));

        assertThatThrownBy(() -> service.disable(principalB, integrationId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTEGRATION_NOT_FOUND));
    }

    @Test
    void multiInstanceExecutionEventIdempotencyDoesNotCollide() {
        UUID prodIntegrationId = UUID.randomUUID();
        UUID stagingIntegrationId = UUID.randomUUID();
        UUID orgId = orgA;

        WorkflowRepository workflowRepo = mock(WorkflowRepository.class);
        ExecutionEventRepository eventRepo = mock(ExecutionEventRepository.class);
        IntegrationRepository intRepo = mock(IntegrationRepository.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        Integration prod = Integration.createConnected(orgId, UUID.randomUUID(), "n8n", "Prod", "1234");
        Integration staging = Integration.createConnected(orgId, UUID.randomUUID(), "n8n", "Staging", "5678");

        when(intRepo.findByIdAndOrganizationId(prodIntegrationId, orgId)).thenReturn(Optional.of(prod));
        when(intRepo.findByIdAndOrganizationId(stagingIntegrationId, orgId)).thenReturn(Optional.of(staging));

        ExecutionEventService eventService = new ExecutionEventService(workflowRepo, eventRepo, intRepo, mapper);
        FlowOpsPrincipal principal = admin(orgId);

        // Same workflow external ID and execution external ID on both instances
        String wfExtId = "common-workflow-1";
        String execExtId = "run-001";
        String stepExtId = "node-http";

        when(eventRepo.findByIntegrationIdAndWorkflowExternalIdAndExecutionExternalIdAndStepExternalId(
                prodIntegrationId, wfExtId, execExtId, stepExtId)).thenReturn(Optional.empty());
        when(eventRepo.findByIntegrationIdAndWorkflowExternalIdAndExecutionExternalIdAndStepExternalId(
                stagingIntegrationId, wfExtId, execExtId, stepExtId)).thenReturn(Optional.empty());

        UUID wfRef = UUID.randomUUID();

        var prodReq = new ExecutionEventEnvelopes.IngestExecutionEventRequest(
                "n8n", wfExtId, execExtId, stepExtId, "HTTP Request", "SUCCEEDED",
                Instant.now(), Instant.now(), Instant.now(), 150L, 0, 100, 200,
                5, Map.of("count", "number"), 200, "COMPLETE", 0,
                null, null, Map.of(), "Common Workflow", prodIntegrationId, wfRef);

        var stagingReq = new ExecutionEventEnvelopes.IngestExecutionEventRequest(
                "n8n", wfExtId, execExtId, stepExtId, "HTTP Request", "SUCCEEDED",
                Instant.now(), Instant.now(), Instant.now(), 210L, 0, 100, 200,
                5, Map.of("count", "number"), 200, "COMPLETE", 0,
                null, null, Map.of(), "Common Workflow", stagingIntegrationId, wfRef);

        ExecutionEvent prodEvent = eventService.ingestEventInternal(principal, prodReq);
        ExecutionEvent stagingEvent = eventService.ingestEventInternal(principal, stagingReq);

        assertThat(prodEvent).isNotNull();
        assertThat(stagingEvent).isNotNull();
        assertThat(prodEvent.getIntegrationId()).isEqualTo(prodIntegrationId);
        assertThat(stagingEvent.getIntegrationId()).isEqualTo(stagingIntegrationId);
        assertThat(prodEvent.getItemCount()).isEqualTo(5);
        assertThat(prodEvent.getHttpStatus()).isEqualTo(200);
        assertThat(prodEvent.getDataCompleteness()).isEqualTo("COMPLETE");
    }
}
