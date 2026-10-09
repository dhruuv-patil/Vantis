package com.flowops.integration.sync;

import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.IntegrationSyncState;
import com.flowops.domain.IntegrationWorkflow;
import com.flowops.execution.ExecutionEventService;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.ExecutionStatus;
import com.flowops.integration.provider.ExternalExecution;
import com.flowops.integration.provider.ExternalNodeExecution;
import com.flowops.integration.provider.ExternalWorkflow;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.ProviderAuthenticationException;
import com.flowops.integration.provider.ProviderCapabilities;
import com.flowops.integration.provider.ProviderConfigurationException;
import com.flowops.integration.provider.ProviderConnectionException;
import com.flowops.integration.provider.ProviderRateLimitException;
import com.flowops.integration.provider.ProviderUnavailableException;
import com.flowops.integration.provider.SyncCursor;
import com.flowops.integration.provider.WorkflowProvider;
import com.flowops.integration.provider.WorkflowProviderRegistry;
import com.flowops.integration.IntegrationWorkflowService;
import com.flowops.reliability.detect.ReliabilityDetectionService;
import com.flowops.reliability.telemetry.TelemetryCollector;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.IntegrationSyncStateRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates incremental sync for a provider integration.
 *
 * <p>Flow per integration:
 * 1. Load integration + sync state (org-scoped)
 * 2. Check soft-disabled state and circuit breaker backoff
 * 3. Decrypt credentials once
 * 4. Resolve provider from registry
 * 5. Page through executions (fetchExecutions with cursor)
 * 6. For monitored executions: fetch node executions using capped parallelism (max 4 per integration)
 * 7. Normalize and ingest as ExecutionEvents (idempotent via composite unique key)
 * 8. Track touched external workflows and new run IDs
 * 9. Only after page fully succeeds: persist cursor + status = HEALTHY
 * 10. For touched workflows: TelemetryCollector.captureExternal(...)
 * 11. For new runs: ReliabilityDetectionService.detectInExecution(...)
 * 12. On failure: circuit breaker backoff, mark sync state with granular error category
 */
@Service
public class IntegrationSyncService {

    private static final Logger log =
            LoggerFactory.getLogger(IntegrationSyncService.class);

    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository credentialsRepo;
    private final IntegrationWorkflowRepository workflowRepo;
    private final IntegrationSyncStateRepository syncStateRepo;
    private final CredentialCipher cipher;
    private final WorkflowProviderRegistry providerRegistry;
    private final IntegrationWorkflowService workflowService;
    private final ExecutionEventService eventService;
    private final TelemetryCollector telemetryCollector;
    private final ReliabilityDetectionService detectionService;
    private final IntegrationCircuitBreaker circuitBreaker;

    // Bounded executor for node detail retrieval (capped parallelism per integration)
    private final ExecutorService nodeFetchExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "integration-node-fetcher");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public IntegrationSyncService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentialsRepo,
            IntegrationWorkflowRepository workflowRepo,
            IntegrationSyncStateRepository syncStateRepo,
            CredentialCipher cipher,
            WorkflowProviderRegistry providerRegistry,
            IntegrationWorkflowService workflowService,
            ExecutionEventService eventService,
            TelemetryCollector telemetryCollector,
            ReliabilityDetectionService detectionService,
            @Autowired(required = false) IntegrationCircuitBreaker circuitBreaker) {

        this.integrations = integrations;
        this.credentialsRepo = credentialsRepo;
        this.workflowRepo = workflowRepo;
        this.syncStateRepo = syncStateRepo;
        this.cipher = cipher;
        this.providerRegistry = providerRegistry;
        this.workflowService = workflowService;
        this.eventService = eventService;
        this.telemetryCollector = telemetryCollector;
        this.detectionService = detectionService;
        this.circuitBreaker = circuitBreaker;
    }

    public IntegrationSyncService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentialsRepo,
            IntegrationWorkflowRepository workflowRepo,
            IntegrationSyncStateRepository syncStateRepo,
            CredentialCipher cipher,
            WorkflowProviderRegistry providerRegistry,
            IntegrationWorkflowService workflowService,
            ExecutionEventService eventService,
            TelemetryCollector telemetryCollector,
            ReliabilityDetectionService detectionService) {

        this(integrations, credentialsRepo, workflowRepo, syncStateRepo, cipher,
                providerRegistry, workflowService, eventService, telemetryCollector,
                detectionService, null);
    }

    /**
     * Runs a sync for the given integration.
     */
    @Transactional
    public SyncResult sync(
            FlowOpsPrincipal principal,
            UUID integrationId) {

        UUID orgId = principal.organizationId();

        Integration integration =
                integrations.findByIdAndOrganizationId(
                                integrationId,
                                orgId)
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Integration not found"));

        if (integration.isDisabled()) {
            log.debug("Integration {} is soft-disabled; skipping sync.", integrationId);
            return new SyncResult(0, 0, 0, 0, "DISABLED");
        }

        if (circuitBreaker != null && !circuitBreaker.allowExecution(integrationId)) {
            long remainingSeconds = circuitBreaker.getRemainingBackoffSeconds(integrationId);
            log.info("Integration {} circuit breaker active; backing off ({}s remaining).",
                    integrationId, remainingSeconds);
            return new SyncResult(0, 0, 0, 0, "CIRCUIT_OPEN_BACKOFF");
        }

        IntegrationSyncState syncState =
                syncStateRepo
                        .findByOrganizationIdAndIntegrationId(
                                orgId,
                                integrationId)
                        .orElseGet(() -> {
                            IntegrationSyncState state =
                                    IntegrationSyncState.create(
                                            orgId,
                                            integrationId);
                            return syncStateRepo.save(state);
                        });

        // Get monitored workflows
        List<IntegrationWorkflow> monitored =
                workflowRepo
                        .findByIntegrationIdAndMonitoringEnabledTrue(
                                integrationId);

        if (monitored.isEmpty()) {
            syncState.markSynced();
            syncStateRepo.save(syncState);
            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess(integrationId);
            }

            return new SyncResult(
                    0,
                    0,
                    0,
                    0,
                    "NO_MONITORED_WORKFLOWS");
        }

        Set<String> monitoredExternalIds = new HashSet<>();
        for (IntegrationWorkflow workflow : monitored) {
            monitoredExternalIds.add(
                    workflow.getProviderWorkflowId());
        }

        // Decrypt credentials once
        IntegrationCredential credEntity =
                credentialsRepo
                        .findByIntegrationId(integrationId)
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "No credential for integration"));

        Map<String, String> decrypted =
                cipher.decryptToMap(
                        credEntity.getCiphertext());

        DecryptedCredentials creds =
                new DecryptedCredentials(decrypted);

        // Resolve provider
        WorkflowProvider provider =
                providerRegistry.getByWire(
                        integration.getType());

        // Sync loop
        SyncCursor cursor =
                new SyncCursor(
                        syncState.getCursor() != null
                                ? syncState.getCursor()
                                : "");

        int totalWorkflows = 0;
        int totalExecutions = 0;
        int totalEvents = 0;
        int duplicates = 0;

        Set<UUID> touchedExternalWorkflowRefs = new HashSet<>();
        List<UUID> newRunUuids = new ArrayList<>();

        try {
            while (true) {
                var page =
                        provider.fetchExecutions(
                                new IntegrationContext(
                                        integrationId,
                                        orgId,
                                        creds),
                                cursor);

                if (page.items().isEmpty()) {
                    break;
                }

                // Filter monitored executions on current page
                List<ExternalExecution> monitoredPageExecs = new ArrayList<>();
                for (ExternalExecution exec : page.items()) {
                    if (monitoredExternalIds.contains(exec.workflowExternalId())) {
                        monitoredPageExecs.add(exec);
                    }
                }

                // Capped parallelism for node execution fetching (max 4 concurrent HTTP calls)
                Map<String, List<ExternalNodeExecution>> nodesByExecId =
                        fetchNodeExecutionsWithCappedParallelism(
                                provider,
                                new IntegrationContext(integrationId, orgId, creds),
                                monitoredPageExecs);

                for (ExternalExecution exec : monitoredPageExecs) {
                    IntegrationWorkflow extWf =
                            workflowService.getOrCreate(
                                    integration,
                                    exec.workflowExternalId(),
                                    exec.metadata());

                    totalWorkflows++;

                    List<ExternalNodeExecution> nodes =
                            nodesByExecId.getOrDefault(exec.externalId(), List.of());

                    // Ingest events
                    var batchRequest =
                            buildBatchRequest(
                                    integration,
                                    extWf,
                                    exec,
                                    nodes);

                    var batchResult =
                            eventService.ingestBatch(
                                    principal,
                                    batchRequest);

                    totalExecutions++;
                    totalEvents += batchResult.accepted();
                    duplicates += batchResult.rejected();

                    touchedExternalWorkflowRefs.add(extWf.getId());

                    UUID runUuid = uuidFromParts(integrationId, exec.externalId());
                    newRunUuids.add(runUuid);
                }

                cursor = page.nextCursor();
                if (!page.hasMore()) {
                    break;
                }
            }

            // Persist cursor and mark healthy
            Instant previousSuccessAt = syncState.getLastSuccessAt();

            syncState.updateCursor(cursor.value());
            syncState.markSynced();
            syncStateRepo.save(syncState);

            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess(integrationId);
            }

            // Update last_synced_at on touched workflows
            for (UUID extWfId : touchedExternalWorkflowRefs) {
                workflowRepo
                        .findById(extWfId)
                        .ifPresent(w -> {
                            w.markSynced();
                            workflowRepo.save(w);
                        });
            }

            // Post-sync telemetry & detection
            for (UUID extWfRef : touchedExternalWorkflowRefs) {
                try {
                    telemetryCollector.captureExternal(
                            extWfRef,
                            previousSuccessAt);
                } catch (Exception e) {
                    log.warn("External telemetry capture failed for {}: {}", extWfRef, e.getMessage());
                }
            }

            for (UUID runUuid : newRunUuids) {
                try {
                    detectionService.detectInExecution(runUuid);
                } catch (Exception e) {
                    log.warn("External detection failed for {}: {}", runUuid, e.getMessage());
                }
            }

            return new SyncResult(
                    touchedExternalWorkflowRefs.size(),
                    totalExecutions,
                    totalEvents,
                    duplicates,
                    "COMPLETED");

        } catch (ProviderAuthenticationException e) {
            integration.markAuthError();
            integrations.save(integration);

            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(integrationId, e);
            }

            syncState.markAuthFailed("Authentication failed: " + e.getMessage());
            syncStateRepo.save(syncState);
            throw e;

        } catch (ProviderRateLimitException e) {
            if (circuitBreaker != null) {
                circuitBreaker.recordRateLimit(integrationId, e.getRetryAfterSeconds());
            }

            syncState.markRateLimited(e.getRetryAfterSeconds(), e.getMessage());
            syncStateRepo.save(syncState);
            throw e;

        } catch (ProviderUnavailableException
                | ProviderConfigurationException
                | ProviderConnectionException e) {

            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(integrationId, e);
            }

            syncState.markUnreachable(e.getMessage());
            syncStateRepo.save(syncState);
            throw e;

        } catch (Exception e) {
            log.error("Sync failed for integration {}: {}", integrationId, e.getMessage(), e);

            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(integrationId, e);
            }

            syncState.markFailed("Unexpected error: " + e.getMessage());
            syncStateRepo.save(syncState);
            throw e;
        }
    }

    /**
     * Bounded historical backfill job.
     * Pages historical executions without blocking normal monitoring.
     */
    @Transactional
    public SyncResult runHistoricalBackfill(
            FlowOpsPrincipal principal,
            UUID integrationId,
            int maxExecutions) {

        UUID orgId = principal.organizationId();
        Integration integration = integrations.findByIdAndOrganizationId(integrationId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Integration not found"));

        if (integration.isDisabled()) {
            return new SyncResult(0, 0, 0, 0, "DISABLED");
        }

        IntegrationSyncState syncState = syncStateRepo
                .findByOrganizationIdAndIntegrationId(orgId, integrationId)
                .orElseGet(() -> syncStateRepo.save(IntegrationSyncState.create(orgId, integrationId)));

        syncState.startBackfill();
        syncStateRepo.save(syncState);

        List<IntegrationWorkflow> monitored = workflowRepo
                .findByIntegrationIdAndMonitoringEnabledTrue(integrationId);
        if (monitored.isEmpty()) {
            syncState.completeBackfill();
            syncStateRepo.save(syncState);
            return new SyncResult(0, 0, 0, 0, "NO_MONITORED_WORKFLOWS");
        }

        Set<String> monitoredExternalIds = new HashSet<>();
        for (IntegrationWorkflow workflow : monitored) {
            monitoredExternalIds.add(workflow.getProviderWorkflowId());
        }

        IntegrationCredential credEntity = credentialsRepo.findByIntegrationId(integrationId)
                .orElseThrow(() -> new IllegalStateException("No credential for integration"));
        DecryptedCredentials creds = new DecryptedCredentials(cipher.decryptToMap(credEntity.getCiphertext()));
        WorkflowProvider provider = providerRegistry.getByWire(integration.getType());

        int totalExecutions = 0;
        int totalEvents = 0;
        int duplicates = 0;
        SyncCursor backfillCursor = SyncCursor.EMPTY;

        try {
            while (totalExecutions < maxExecutions) {
                var page = provider.fetchExecutions(
                        new IntegrationContext(integrationId, orgId, creds), backfillCursor);

                if (page.items().isEmpty()) {
                    break;
                }

                List<ExternalExecution> monitoredPageExecs = page.items().stream()
                        .filter(e -> monitoredExternalIds.contains(e.workflowExternalId()))
                        .toList();

                Map<String, List<ExternalNodeExecution>> nodesByExecId =
                        fetchNodeExecutionsWithCappedParallelism(
                                provider,
                                new IntegrationContext(integrationId, orgId, creds),
                                monitoredPageExecs);

                for (ExternalExecution exec : monitoredPageExecs) {
                    IntegrationWorkflow extWf = workflowService.getOrCreate(
                            integration, exec.workflowExternalId(), exec.metadata());

                    List<ExternalNodeExecution> nodes = nodesByExecId.getOrDefault(exec.externalId(), List.of());
                    var batchRequest = buildBatchRequest(integration, extWf, exec, nodes);
                    var batchResult = eventService.ingestBatch(principal, batchRequest);

                    totalExecutions++;
                    totalEvents += batchResult.accepted();
                    duplicates += batchResult.rejected();
                }

                backfillCursor = page.nextCursor();
                int progress = Math.min(99, (int) ((totalExecutions / (double) Math.max(1, maxExecutions)) * 100));
                syncState.updateBackfill(progress, backfillCursor.value());
                syncStateRepo.save(syncState);

                if (!page.hasMore()) {
                    break;
                }
            }

            syncState.completeBackfill();
            syncStateRepo.save(syncState);

            return new SyncResult(monitored.size(), totalExecutions, totalEvents, duplicates, "BACKFILL_COMPLETED");
        } catch (Exception e) {
            log.error("Backfill failed for integration {}: {}", integrationId, e.getMessage());
            syncState.markFailed("Backfill failed: " + e.getMessage(), "BACKFILL_ERROR");
            syncStateRepo.save(syncState);
            throw e;
        }
    }

    /**
     * Retrieves node executions concurrently with capped parallelism (max 4 per integration).
     * Justification: prevents connection starvation and avoids overwhelming self-hosted n8n instances.
     */
    private Map<String, List<ExternalNodeExecution>> fetchNodeExecutionsWithCappedParallelism(
            WorkflowProvider provider,
            IntegrationContext context,
            List<ExternalExecution> executions) {

        Map<String, List<ExternalNodeExecution>> result = new ConcurrentHashMap<>();
        if (executions.isEmpty()) {
            return result;
        }

        if (!provider.capabilities().nodeExecutionData()) {
            for (ExternalExecution exec : executions) {
                result.put(exec.externalId(), List.of());
            }
            return result;
        }

        final int maxParallelism = 4;
        Semaphore semaphore = new Semaphore(maxParallelism);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (ExternalExecution exec : executions) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        List<ExternalNodeExecution> nodes = provider.fetchNodeExecutions(context, exec);
                        result.put(exec.externalId(), nodes != null ? nodes : List.of());
                    } finally {
                        semaphore.release();
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch node executions for {}: {}", exec.externalId(), e.getMessage());
                    result.put(exec.externalId(), List.of());
                }
            }, nodeFetchExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return result;
    }

    private ExecutionEventEnvelopes.BatchIngestRequest buildBatchRequest(
            Integration integration,
            IntegrationWorkflow extWf,
            ExternalExecution exec,
            List<ExternalNodeExecution> nodes) {

        List<ExecutionEventEnvelopes.IngestExecutionEventRequest> events =
                new ArrayList<>();

        if (nodes.isEmpty()) {
            // When provider does not expose node details (e.g. Make scenarios),
            // ingest a workflow-level execution event so runs are monitored and observed.
            events.add(
                    new ExecutionEventEnvelopes.IngestExecutionEventRequest(
                            integration.getType(),
                            extWf.getProviderWorkflowId(),
                            exec.externalId(),
                            "workflow_run",
                            extWf.getName() != null ? extWf.getName() : "Execution",
                            exec.status().name(),
                            exec.startedAt() != null ? exec.startedAt() : Instant.now(),
                            exec.startedAt(),
                            exec.finishedAt(),
                            durationMs(exec.startedAt(), exec.finishedAt()),
                            0,
                            null, // inputSize
                            null, // outputSize
                            null, // itemCount
                            null, // outputSignature
                            null, // httpStatus
                            "COMPLETE", // dataCompleteness
                            0,    // runIndex
                            exec.status() == ExecutionStatus.FAILED ? "execution_failed" : null,
                            null,
                            exec.metadata(),
                            extWf.getName(),
                            integration.getId(),
                            extWf.getId()
                    ));
        } else {
            for (ExternalNodeExecution node : nodes) {
                events.add(
                        new ExecutionEventEnvelopes.IngestExecutionEventRequest(
                                integration.getType(),
                                extWf.getProviderWorkflowId(),
                                exec.externalId(),
                                node.externalId(),
                                node.name(),
                                node.status().name(),
                                node.startedAt() != null
                                        ? node.startedAt()
                                        : Instant.now(),
                                node.startedAt(),
                                node.finishedAt(),
                                durationMs(
                                        node.startedAt(),
                                        node.finishedAt()),
                                0,
                                node.inputSize() != null
                                        ? node.inputSize().intValue()
                                        : null,
                                node.outputSize() != null
                                        ? node.outputSize().intValue()
                                        : null,
                                node.itemCount(),
                                node.outputSignature(),
                                node.httpStatus(),
                                node.dataCompleteness() != null ? node.dataCompleteness() : "COMPLETE",
                                node.runIndex() != null ? node.runIndex() : 0,
                                node.errorCode() != null
                                        ? node.errorCode()
                                        : (node.status() != null && "FAILED".equalsIgnoreCase(node.status().name())
                                                ? "execution_failed" : null),
                                node.errorMessage(),
                                node.metadata(),
                                extWf.getName(),
                                integration.getId(),
                                extWf.getId()
                        ));
            }
        }

        return new ExecutionEventEnvelopes.BatchIngestRequest(events);
    }

    private Long durationMs(
            Instant start,
            Instant finish) {

        if (start != null && finish != null) {
            return finish.toEpochMilli()
                    - start.toEpochMilli();
        }
        return null;
    }

    private UUID uuidFromParts(
            UUID integrationId,
            String executionExternalId) {

        String combined =
                integrationId.toString()
                        + ":"
                        + executionExternalId;

        return UUID.nameUUIDFromBytes(
                combined.getBytes());
    }

    public record SyncResult(
            int workflows,
            int executions,
            int events,
            int duplicates,
            String status) {
    }
}