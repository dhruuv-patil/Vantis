package com.flowops.execution;

import com.flowops.audit.AuditService;
import com.flowops.domain.AuditAction;
import com.flowops.config.ExecutionProperties;
import com.flowops.repository.WorkflowExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);

    private final WorkflowExecutionRepository executions;
    private final ExecutionStore store;
    private final QueueService queue;
    private final AuditService audit;
    private final ExecutionProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionRecoveryService(
            WorkflowExecutionRepository executions,
            ExecutionStore store,
            QueueService queue,
            AuditService audit,
            ExecutionProperties properties) {
        this.executions = executions;
        this.store = store;
        this.queue = queue;
        this.audit = audit;
        this.properties = properties;
    }

    public ExecutionRecoveryService(
            WorkflowExecutionRepository executions,
            ExecutionStore store,
            QueueService queue,
            AuditService audit) {
        this(executions, store, queue, audit, ExecutionProperties.defaults());
    }

    /**
     * Runs once on application start. The listener swallows its own failures so
     * a transient error during recovery (e.g. DB hiccup) cannot prevent the
     * application from accepting traffic.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void recoverOnStartup() {
        try {
            recover();
        } catch (RuntimeException recoveryFailure) {
            // Never block startup on a recovery error. A supervisor / healthcheck
            // can still see the wedged rows and an operator can re-trigger the
            // sweep by restarting; the alternative (a wedged boot) is worse.
            log.error("Execution recovery sweep failed on startup; some runs may be wedged.",
                    recoveryFailure);
        }
    }

    /**
     * The actual sweep, exposed for tests. Returns the number of executions
     * re-enqueued.
     */
    @Transactional
    public int recover() {
        List<UUID> ids = executions.findRecoverableExecutionIds();
        if (ids.isEmpty()) {
            log.info("Startup recovery sweep: no runs to recover.");
            return 0;
        }
        log.info("Startup recovery sweep: re-enqueueing {} non-terminal run(s).", ids.size());
        int requeued = 0;
        for (UUID id : ids) {
            try {
                int reset = store.resetStuckNodesForRecovery(id);
                queue.enqueue(id);
                requeued++;
                log.info("Recovered execution {} ({} stuck node(s) reset to PENDING).", id, reset);
                // The org id comes from the trusted execution row, never from the
                // actor (there is no actor on a startup recovery). The audit
                // event is written in its own REQUIRES_NEW transaction inside
                // AuditService, so this @Transactional context can safely read
                // before it.
                UUID orgId = store.getExecution(id).getOrganizationId();
                audit.record(
                        orgId,
                        null,
                        null,
                        AuditAction.EXECUTION_RECOVERED,
                        id.toString(),
                        "Recovered execution " + id + " on startup: "
                                + reset + " stuck node(s) reset and the run re-queued.");
            } catch (RuntimeException perRunFailure) {
                // One bad run must not block the rest. The row stays wedged; the
                // next sweep (or an operator) can retry.
                log.warn("Failed to recover execution {}: {}", id, perRunFailure.getMessage());
            }
        }
        log.info("Startup recovery sweep complete: {}/{} run(s) re-enqueued.",
                requeued, ids.size());
        return requeued;
    }

    /**
     * Periodic sweeper to detect orphaned RUNNING executions whose worker crashed or stopped heartbeating.
     */
    @Scheduled(fixedDelayString = "${flowops.execution.recovery-interval:60000}")
    @Transactional
    public int sweepStaleExecutions() {
        try {
            Instant threshold = Instant.now().minus(properties.staleThreshold());
            List<UUID> staleIds = executions.findStaleExecutionIds(threshold);
            if (staleIds.isEmpty()) {
                return 0;
            }
            log.warn("Detected {} stale execution(s) (no heartbeat since {}). Recovering...",
                    staleIds.size(), threshold);
            int recovered = 0;
            for (UUID id : staleIds) {
                try {
                    int reset = store.resetStuckNodesForRecovery(id);
                    queue.enqueue(id);
                    recovered++;
                    UUID orgId = store.getExecution(id).getOrganizationId();
                    audit.record(
                            orgId,
                            null,
                            null,
                            AuditAction.EXECUTION_RECOVERED,
                            id.toString(),
                            "Recovered stale orphaned execution " + id + " (heartbeat stopped before "
                                    + threshold + "): " + reset + " stuck node(s) reset and re-queued.");
                    log.info("Successfully recovered orphaned execution {}.", id);
                } catch (RuntimeException ex) {
                    log.warn("Failed to recover stale execution {}: {}", id, ex.getMessage());
                }
            }
            return recovered;
        } catch (Exception e) {
            log.error("Error during scheduled stale execution sweep: {}", e.getMessage());
            return 0;
        }
    }
}
