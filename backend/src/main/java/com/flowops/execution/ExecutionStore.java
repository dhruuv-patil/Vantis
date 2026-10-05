package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.domain.AiAgent;
import com.flowops.domain.ExecutionLog;
import com.flowops.domain.ExecutionNode;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.LogLevel;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.WorkflowExecution;
import com.flowops.domain.NodeExecutionAttempt;
import com.flowops.repository.AiAgentRepository;
import com.flowops.repository.ExecutionLogRepository;
import com.flowops.repository.ExecutionNodeRepository;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.NodeExecutionAttemptRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engine's persistence boundary. Each method is its own short transaction, so
 * the {@link ExecutionEngine} — which orchestrates on a worker thread and may sleep
 * for delays or park for approvals — never holds a database connection open across
 * a wait. Every state change the engine makes is committed immediately, so a
 * concurrent monitor read (or the SSE snapshot) always sees live progress.
 *
 * <p>These reads are intentionally <em>not</em> org-scoped: the engine only ever
 * acts on an execution id that {@code ExecutionService} already resolved within the
 * caller's tenant. Tenant isolation lives at the service edge, not here.
 */
@Service
public class ExecutionStore {

    private final WorkflowExecutionRepository executions;
    private final ExecutionNodeRepository nodes;
    private final ExecutionLogRepository logs;
    private final WorkflowVersionRepository versions;
    private final AiAgentRepository aiAgents;
    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository integrationCredentials;
    private final NodeExecutionAttemptRepository nodeAttempts;

    public ExecutionStore(
            WorkflowExecutionRepository executions,
            ExecutionNodeRepository nodes,
            ExecutionLogRepository logs,
            WorkflowVersionRepository versions,
            AiAgentRepository aiAgents,
            IntegrationRepository integrations,
            IntegrationCredentialRepository integrationCredentials,
            NodeExecutionAttemptRepository nodeAttempts) {
        this.executions = executions;
        this.nodes = nodes;
        this.logs = logs;
        this.versions = versions;
        this.aiAgents = aiAgents;
        this.integrations = integrations;
        this.integrationCredentials = integrationCredentials;
        this.nodeAttempts = nodeAttempts;
    }

    @Transactional(readOnly = true)
    public WorkflowExecution getExecution(UUID executionId) {
        return executions.findById(executionId).orElseThrow(
                () -> new IllegalStateException("Execution vanished mid-run: " + executionId));
    }

    @Transactional(readOnly = true)
    public JsonNode getGraph(UUID versionId) {
        return versions.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("Version vanished mid-run: " + versionId))
                .getGraph();
    }

    /**
     * Resolves a saved AI agent for an {@code ai_agent} node, scoped to the run's
     * organization. The org id comes from the trusted execution record — never from
     * the graph — so a node cannot reference another tenant's agent. An agent owned
     * by another org is indistinguishable from one that does not exist (empty).
     */
    @Transactional(readOnly = true)
    public Optional<AiAgent> findAiAgentForRun(UUID organizationId, UUID agentId) {
        return aiAgents.findByIdAndOrganizationId(agentId, organizationId);
    }

    /**
     * Resolves the connected Slack credential for a run's organization, or empty when
     * the org has none. Like {@link #findAiAgentForRun}, the org id comes from the
     * trusted execution record — never the graph — so this cannot be a cross-tenant
     * channel. The returned ciphertext is decrypted in memory at the engine boundary
     * to deliver a message; it is never persisted into the run snapshot.
     */
    @Transactional(readOnly = true)
    public Optional<IntegrationCredential> findConnectedSlackCredential(UUID organizationId) {
        return integrations
                .findByOrganizationIdAndTypeAndStatus(
                        organizationId, Integration.TYPE_SLACK, Integration.STATUS_CONNECTED)
                .flatMap(integration -> integrationCredentials.findByIntegrationId(integration.getId()));
    }

    /**
     * Resolves a connected integration by its own id within the run's organization.
     * Unlike {@link #findConnectedSlackCredential}, this is provider-agnostic: a
     * notification node that references {@code integrationId} in its config resolves
     * the stored credential here (org id from the trusted execution record, never the
     * graph). The returned credential's ciphertext is decrypted in memory at the
     * engine boundary and never persisted into the run snapshot.
     */
    @Transactional(readOnly = true)
    public Optional<IntegrationWithCredential> findConnectedIntegration(
            UUID organizationId, UUID integrationId) {
        return integrations
                .findByIdAndOrganizationId(integrationId, organizationId)
                .filter(integration -> Integration.STATUS_CONNECTED.equals(integration.getStatus()))
                .flatMap(integration -> integrationCredentials
                        .findByIntegrationId(integration.getId())
                        .map(credential -> new IntegrationWithCredential(integration, credential)));
    }

    /** A connected integration row plus its stored (encrypted) credential. */
    public record IntegrationWithCredential(
            Integration integration,
            IntegrationCredential credential) {
    }

    @Transactional(readOnly = true)
    public List<ExecutionNode> getNodes(UUID executionId) {
        return nodes.findByExecutionIdOrderByCreatedAtAsc(executionId);
    }

    @Transactional(readOnly = true)
    public int logCount(UUID executionId) {
        return logs.countByExecutionId(executionId);
    }

    @Transactional
    public void saveExecution(WorkflowExecution execution) {
        executions.save(execution);
    }

    @Transactional
    public void saveNode(ExecutionNode node) {
        nodes.save(node);
    }

    /**
     * Reset every node of {@code executionId} that is not yet in a terminal or
     * waiting state back to {@code PENDING}, so the engine can re-execute them
     * on the next run. Used only by the startup recovery sweep: the worker
     * thread that owned the run died with the JVM, so any per-node
     * {@code RUNNING} row is no longer being worked on. Already-succeeded nodes
     * keep their outputs (the engine reuses them), failed nodes are re-attempted
     * within the same per-node attempt budget, and a {@code WAITING} node is
     * left alone — it represents a real human-decision pause, not a crash.
     */
    @Transactional
    public int resetStuckNodesForRecovery(UUID executionId) {
        int reset = 0;
        for (ExecutionNode node : nodes.findByExecutionIdOrderByCreatedAtAsc(executionId)) {
            if (node.getStatus() == NodeRunStatus.RUNNING || node.getStatus() == NodeRunStatus.PENDING) {
                node.resetForRetry();
                nodes.save(node);
                reset++;
            }
        }
        return reset;
    }

    @Transactional
    public ExecutionLog appendLog(UUID executionId, String nodeId, LogLevel level, String message, int seq) {
        return logs.save(ExecutionLog.create(executionId, nodeId, level, message, seq));
    }

    @Transactional
    public void saveAttempt(NodeExecutionAttempt attempt) {
        nodeAttempts.save(attempt);
    }

    @Transactional(readOnly = true)
    public List<NodeExecutionAttempt> getAttempts(UUID executionId) {
        return nodeAttempts.findByExecutionIdOrderByStartedAtAsc(executionId);
    }

    @Transactional
    public void recordHeartbeat(UUID executionId, String workerId) {
        executions.findById(executionId).ifPresent(e -> {
            e.recordHeartbeat();
            if (workerId != null && !workerId.isBlank()) {
                e.assignWorker(workerId);
            }
            executions.save(e);
        });
    }
}
