package com.flowops.integration.api;

import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.IntegrationWorkflow;
import com.flowops.domain.Role;
import com.flowops.integration.IntegrationService;
import com.flowops.integration.IntegrationWorkflowService;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.ExternalWorkflow;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.WorkflowProvider;
import com.flowops.integration.provider.WorkflowProviderRegistry;
import com.flowops.integration.sync.IntegrationSyncService;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationSyncStateRepository;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
@Tag(name = "Integrations — Monitored Workflows")
public class MonitoredWorkflowController {

    private final IntegrationService integrationService;
    private final IntegrationWorkflowService workflowService;
    private final IntegrationSyncService syncService;
    private final IntegrationSyncStateRepository syncStateRepo;
    private final IntegrationCredentialRepository credentialsRepo;
    private final CredentialCipher cipher;
    private final WorkflowProviderRegistry providerRegistry;

    public MonitoredWorkflowController(
            IntegrationService integrationService,
            IntegrationWorkflowService workflowService,
            IntegrationSyncService syncService,
            IntegrationSyncStateRepository syncStateRepo,
            IntegrationCredentialRepository credentialsRepo,
            CredentialCipher cipher,
            WorkflowProviderRegistry providerRegistry) {

        this.integrationService = integrationService;
        this.workflowService = workflowService;
        this.syncService = syncService;
        this.syncStateRepo = syncStateRepo;
        this.credentialsRepo = credentialsRepo;
        this.cipher = cipher;
        this.providerRegistry = providerRegistry;
    }

    @GetMapping("/{id}/workflows")
    @Operation(summary = "Discover workflows from an integration")
    public MonitoredWorkflowsResponse listWorkflows(
            @PathVariable UUID id) {

        FlowOpsPrincipal principal = AuthenticatedUser.require();

        Integration integration =
                integrationService.getEntity(principal, id);

        IntegrationCredential credential =
                credentialsRepo.findByIntegrationId(integration.getId())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.INTEGRATION_NOT_FOUND,
                                "Integration credentials not found"));

        Map<String, String> decrypted =
                cipher.decryptToMap(credential.getCiphertext());

        DecryptedCredentials credentials =
                new DecryptedCredentials(decrypted);

        WorkflowProvider provider;
        try {
            provider = providerRegistry.getByWire(integration.getType());
        } catch (Exception e) {
            return new MonitoredWorkflowsResponse(List.of());
        }

        IntegrationContext context =
                new IntegrationContext(
                        integration.getId(),
                        integration.getOrganizationId(),
                        credentials);

        List<ExternalWorkflow> discovered =
                provider.discoverWorkflows(context);

        List<IntegrationWorkflow> monitored =
                workflowService.findByIntegrationIdAndMonitoringEnabledTrue(
                        integration.getId());

        List<MonitoredWorkflowInfo> workflows =
                discovered.stream()
                        .map(w -> {

                            IntegrationWorkflow existing =
                                    monitored.stream()
                                            .filter(existingWorkflow ->
                                                    w.externalId().equals(
                                                            existingWorkflow
                                                                    .getProviderWorkflowId()))
                                            .findFirst()
                                            .orElse(null);

                            boolean monitoring =
                                    existing != null
                                            && existing.isMonitoringEnabled();

                            Instant lastSyncedAt =
                                    existing != null
                                            ? existing.getLastSyncedAt()
                                            : null;

                            return new MonitoredWorkflowInfo(
                                    w.externalId(),
                                    w.name(),
                                    w.status(),
                                    monitoring,
                                    lastSyncedAt);
                        })
                        .toList();

        return new MonitoredWorkflowsResponse(workflows);
    }

    @PostMapping("/{id}/workflows/{workflowId}/monitor")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Start or stop monitoring a workflow")
    public MonitorActionResponse monitorWorkflow(
            @PathVariable UUID id,
            @PathVariable String workflowId,
            @Valid @RequestBody MonitorActionRequest request) {

        FlowOpsPrincipal principal =
                AuthenticatedUser.requireRole(Role.ADMIN);

        Integration integration =
                integrationService.getEntity(principal, id);

        if (workflowId == null || workflowId.isBlank()) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_NOT_FOUND,
                    "Workflow ID is required");
        }

        if (request.monitoring()) {

            IntegrationCredential credential =
                    credentialsRepo.findByIntegrationId(integration.getId())
                            .orElseThrow(() -> new ApiException(
                                    ErrorCode.INTEGRATION_NOT_FOUND,
                                    "Integration credentials not found"));

            Map<String, String> decrypted =
                    cipher.decryptToMap(credential.getCiphertext());

            DecryptedCredentials credentials =
                    new DecryptedCredentials(decrypted);

            WorkflowProvider provider =
                    providerRegistry.getByWire(integration.getType());

            IntegrationContext context =
                    new IntegrationContext(
                            integration.getId(),
                            integration.getOrganizationId(),
                            credentials);

            List<ExternalWorkflow> discovered =
                    provider.discoverWorkflows(context);

            ExternalWorkflow externalWorkflow =
                    discovered.stream()
                            .filter(w ->
                                    workflowId.equals(w.externalId()))
                            .findFirst()
                            .orElseThrow(() -> new ApiException(
                                    ErrorCode.INTEGRATION_NOT_FOUND,
                                    "Workflow not found in provider"));

            IntegrationWorkflow workflow =
                    workflowService.getOrCreate(
                            integration,
                            externalWorkflow.externalId(),
                            externalWorkflow.metadata());

            workflow.updateFromProvider(
                    externalWorkflow.name(),
                    externalWorkflow.status(),
                    externalWorkflow.metadata());

            workflow.enableMonitoring();

            workflowService.save(workflow);

        } else {

            IntegrationWorkflow workflow =
                    workflowService
                            .findByIntegrationIdAndProviderWorkflowId(
                                    integration.getId(),
                                    workflowId)
                            .orElseThrow(() -> new ApiException(
                                    ErrorCode.INTEGRATION_NOT_FOUND,
                                    "Workflow not found"));

            workflow.disableMonitoring();

            workflowService.save(workflow);
        }

        return new MonitorActionResponse(
                true,
                request.monitoring()
                        ? "MONITORING_STARTED"
                        : "MONITORING_STOPPED");
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "Trigger a manual sync for the integration")
    public SyncActionResponse syncIntegration(
            @PathVariable UUID id) {

        FlowOpsPrincipal principal =
                AuthenticatedUser.requireRole(Role.ADMIN);

        integrationService.getEntity(principal, id);

        var result = syncService.sync(principal, id);

        return new SyncActionResponse(
                result.workflows(),
                result.executions(),
                result.events(),
                result.duplicates(),
                result.status());
    }

    @GetMapping("/{id}/sync")
    @Operation(summary = "Get sync status for an integration")
    public SyncStatusResponse getSyncStatus(
            @PathVariable UUID id) {

        FlowOpsPrincipal principal =
                AuthenticatedUser.require();

        integrationService.getEntity(principal, id);

        var syncState =
                syncStateRepo
                        .findByOrganizationIdAndIntegrationId(
                                principal.organizationId(),
                                id)
                        .orElse(null);

        if (syncState == null) {
            return new SyncStatusResponse(
                    "NEW",
                    null,
                    null,
                    null);
        }

        return new SyncStatusResponse(
                syncState.getStatus(),
                syncState.getLastSyncedAt(),
                syncState.getLastSuccessAt(),
                syncState.getLastError());
    }

    // ---- DTOs ----

    public record MonitoredWorkflowsResponse(
            List<MonitoredWorkflowInfo> workflows) {
    }

    public record MonitoredWorkflowInfo(
            String id,
            String name,
            String status,
            boolean monitoring,
            Instant lastSyncedAt) {
    }

    public record MonitorActionRequest(
            boolean monitoring) {
    }

    public record MonitorActionResponse(
            boolean success,
            String status) {
    }

    public record SyncActionResponse(
            int workflows,
            int executions,
            int events,
            int duplicates,
            String status) {
    }

    public record SyncStatusResponse(
            String status,
            Instant lastSyncAt,
            Instant lastSuccessfulSyncAt,
            String error) {
    }
}