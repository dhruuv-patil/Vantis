package com.flowops.integration.sync;

import com.flowops.config.ProviderSyncProperties;
import com.flowops.domain.Integration;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.IntegrationSyncStateRepository;
import com.flowops.repository.IntegrationWorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled sync for all connected provider integrations with monitored workflows.
 *
 * <p>Resilience and Concurrency Features:
 * <ul>
 *   <li>Excludes soft-disabled integrations.</li>
 *   <li>Bounded global concurrency (max 8 concurrent integration syncs) to prevent thread exhaustion.</li>
 *   <li>Heartbeat and stall detection: tracks sweep execution times and flags if sync workers stall.</li>
 *   <li>Swallows per-integration errors into sync state so failure of one does not interrupt the sweep.</li>
 * </ul>
 */
@Service
public class IntegrationSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncScheduler.class);

    private final IntegrationRepository integrations;
    private final IntegrationWorkflowRepository workflowRepo;
    private final IntegrationSyncStateRepository syncStateRepo;
    private final IntegrationSyncService syncService;
    private final ProviderSyncProperties properties;

    private final AtomicReference<Instant> lastSweepStarted = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> lastSweepCompleted = new AtomicReference<>(Instant.now());

    // Bounded global concurrency for cross-tenant integration syncing
    private final Semaphore globalSyncSemaphore = new Semaphore(8);
    private final ExecutorService sweepExecutor = Executors.newFixedThreadPool(
            8,
            r -> {
                Thread t = new Thread(r, "integration-sync-worker");
                t.setDaemon(true);
                return t;
            });

    public IntegrationSyncScheduler(
            IntegrationRepository integrations,
            IntegrationWorkflowRepository workflowRepo,
            IntegrationSyncStateRepository syncStateRepo,
            IntegrationSyncService syncService,
            ProviderSyncProperties properties) {
        this.integrations = integrations;
        this.workflowRepo = workflowRepo;
        this.syncStateRepo = syncStateRepo;
        this.syncService = syncService;
        this.properties = properties;
    }

    /**
     * Runs every {@code flowops.providers.sync-delay} (default 1m).
     * Scans all connected, non-disabled integrations that have monitored workflows.
     */
    @Scheduled(fixedDelayString = "${flowops.providers.sync-delay:1m}",
            initialDelayString = "${flowops.providers.sync-delay:1m}")
    public void sweep() {
        lastSweepStarted.set(Instant.now());
        List<Integration> connectedIntegrations = findConnectedIntegrationsWithMonitoredWorkflows();
        log.debug("Provider sync sweep: {} active integration(s) with monitored workflows", connectedIntegrations.size());

        List<CompletableFuture<Void>> futures = connectedIntegrations.stream()
                .map(integration -> CompletableFuture.runAsync(() -> {
                    try {
                        globalSyncSemaphore.acquire();
                        try {
                            syncIntegration(integration);
                        } finally {
                            globalSyncSemaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Sync interrupted for integration {}", integration.getId());
                    } catch (Exception e) {
                        log.debug("Sync failed for integration {}: {}", integration.getId(), e.getMessage());
                    }
                }, sweepExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error waiting for sync sweep completion: {}", e.getMessage());
        } finally {
            lastSweepCompleted.set(Instant.now());
        }
    }

    private List<Integration> findConnectedIntegrationsWithMonitoredWorkflows() {
        List<Integration> connected = integrations.findByStatusAndDisabledAtIsNull(Integration.STATUS_CONNECTED);
        return connected.stream()
                .filter(i -> !i.isDisabled() && workflowRepo.existsByIntegrationIdAndMonitoringEnabledTrue(i.getId()))
                .toList();
    }

    @Transactional
    public void syncIntegration(Integration integration) {
        FlowOpsPrincipal schedulerPrincipal = new FlowOpsPrincipal(
                UUID.fromString("00000000-0000-0000-0000-000000000000"), // system user
                UUID.fromString("00000000-0000-0000-0000-000000000000"), // no session
                integration.getOrganizationId(),
                com.flowops.domain.Role.OWNER,
                "system@flowops.internal"
        );
        syncService.sync(schedulerPrincipal, integration.getId());
    }

    /**
     * Diagnostic: checks if sync workers have stalled (e.g. no completed sweep in > 5 minutes).
     */
    public boolean isWorkerStalled() {
        Instant completed = lastSweepCompleted.get();
        return Duration.between(completed, Instant.now()).toMinutes() > 5;
    }

    public WorkerHealth getWorkerHealth() {
        boolean stalled = isWorkerStalled();
        return new WorkerHealth(
                !stalled,
                lastSweepStarted.get(),
                lastSweepCompleted.get(),
                stalled ? "Sync workers stalled (no completed sweep in >5m)" : "Healthy");
    }

    public record WorkerHealth(
            boolean healthy,
            Instant lastSweepStartedAt,
            Instant lastSweepCompletedAt,
            String status
    ) {
    }
}