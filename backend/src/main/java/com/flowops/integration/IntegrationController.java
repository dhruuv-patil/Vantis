package com.flowops.integration;

import com.flowops.api.IntegrationEnvelopes;
import com.flowops.api.IntegrationResponse;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integrations marketplace CRUD (M5).
 *
 * <p>The tenant is always the caller's current organization, read from the principal
 * by {@link AuthenticatedUser}. No endpoint accepts an organization id. Reading is
 * open to any member; connecting and disconnecting are administrative actions gated to
 * {@link Role#ADMIN} and above.
 */
@RestController
@RequestMapping("/api/integrations")
@Tag(name = "Integrations")
public class IntegrationController {

    private final IntegrationService integrationService;
    private final com.flowops.integration.sync.IntegrationSyncScheduler syncScheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationController(
            IntegrationService integrationService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.flowops.integration.sync.IntegrationSyncScheduler syncScheduler) {
        this.integrationService = integrationService;
        this.syncScheduler = syncScheduler;
    }

    public IntegrationController(IntegrationService integrationService) {
        this(integrationService, null);
    }

    @GetMapping("/health")
    @Operation(summary = "Get overall integration sync health and worker status")
    public IntegrationSyncHealthResponse getHealth() {
        var principal = AuthenticatedUser.require();
        var workerHealth = syncScheduler != null ? syncScheduler.getWorkerHealth() : null;
        boolean serviceHealthy = workerHealth == null || workerHealth.healthy();
        var summaries = integrationService.getHealthSummaries(principal);
        return new IntegrationSyncHealthResponse(
                serviceHealthy,
                serviceHealthy ? "Monitoring service healthy" : workerHealth.status(),
                workerHealth,
                summaries);
    }

    @GetMapping
    @Operation(summary = "List integrations in the current organization")
    public IntegrationEnvelopes.Integrations list() {
        return integrationService.list(AuthenticatedUser.require());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an integration (masked; never returns the secret)")
    public IntegrationResponse get(@PathVariable UUID id) {
        return integrationService.get(AuthenticatedUser.require(), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Connect an integration by storing its encrypted credential")
    public IntegrationResponse connect(@Valid @RequestBody ConnectIntegrationRequest request) {
        return integrationService.connect(AuthenticatedUser.requireRole(Role.ADMIN), request);
    }

    @org.springframework.web.bind.annotation.PutMapping(path = "/{id}/credentials", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update or rotate credentials for an integration")
    public IntegrationResponse updateCredentials(
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, String> credentials) {
        return integrationService.updateCredentials(AuthenticatedUser.requireRole(Role.ADMIN), id, credentials);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect an integration and destroy its stored secret")
    public ResponseEntity<Void> disconnect(@PathVariable UUID id) {
        integrationService.disconnect(AuthenticatedUser.requireRole(Role.ADMIN), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Pause monitoring for an integration (soft-disable)")
    public IntegrationResponse disable(@PathVariable UUID id) {
        return integrationService.disable(AuthenticatedUser.requireRole(Role.ADMIN), id);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Resume monitoring for a soft-disabled integration")
    public IntegrationResponse enable(@PathVariable UUID id) {
        return integrationService.enable(AuthenticatedUser.requireRole(Role.ADMIN), id);
    }

    @GetMapping("/{id}/sync-state")
    @Operation(summary = "Get the sync state and health for an integration")
    public com.flowops.domain.IntegrationSyncState getSyncState(@PathVariable UUID id) {
        return integrationService.getSyncState(AuthenticatedUser.require(), id);
    }

    public record IntegrationSyncHealthResponse(
            boolean serviceHealthy,
            String message,
            com.flowops.integration.sync.IntegrationSyncScheduler.WorkerHealth workerHealth,
            java.util.List<IntegrationHealthSummary> integrations
    ) {}

    public record IntegrationHealthSummary(
            UUID integrationId,
            String name,
            String type,
            String healthStatus,
            java.time.Instant lastSuccessfulSyncAt,
            String lastErrorCategory,
            boolean disabled,
            boolean backfilling,
            int backfillProgress
    ) {}
}
