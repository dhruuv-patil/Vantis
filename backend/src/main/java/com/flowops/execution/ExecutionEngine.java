package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.ExecutionLogResponse;
import com.flowops.api.ExecutionNodeResponse;
import com.flowops.api.ExecutionSummaryResponse;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.config.ExecutionProperties;
import com.flowops.domain.AiAgent;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.ExecutionLog;
import com.flowops.domain.ExecutionNode;
import com.flowops.domain.ExecutionStatus;

import com.flowops.domain.Incident;

import com.flowops.domain.Integration;
import com.flowops.integration.delivery.NotificationProviderRegistry;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.LogLevel;
import com.flowops.domain.NodeRunStatus;
import com.flowops.domain.NotificationLevel;
import com.flowops.domain.WorkflowExecution;
import com.flowops.notification.NotificationService;
import com.flowops.repository.IncidentRepository;
import com.flowops.reliability.detect.ReliabilityDetectionService;
import com.flowops.reliability.telemetry.TelemetryCollector;
import com.flowops.domain.NodeExecutionAttempt;
import com.flowops.reliability.CircuitBreakerService;
import com.flowops.reliability.DependencyIntelligenceService;
import com.flowops.workflow.graph.GraphDocument;
import com.flowops.workflow.graph.GraphEdge;
import com.flowops.workflow.graph.GraphNode;
import com.flowops.workflow.nodes.NodeDefinition;
import com.flowops.workflow.nodes.NodeRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final ExecutionStore store;
    private final IncidentRepository incidents;
    private final ExecutionEvents events;
    private final NodeExecutorRegistry executors;
    private final NodeRegistry nodeRegistry;
    private final VariableInterpolator interpolator;
    private final ObjectMapper mapper;
    private final ExecutionProperties properties;
    private final CredentialCipher cipher;
    private final NotificationService notifications;
    private final TelemetryCollector telemetry;
    private final ReliabilityDetectionService detection;
    private final NotificationProviderRegistry notificationProviders;
    private final WebhookEventDispatcher webhookEvents;
    private final NodeIdempotencyService idempotencyService;
    private final CircuitBreakerService circuitBreakerService;
    private final RetryPolicyClassifier retryClassifier;
    private final DeadLetterService deadLetterService;
    private final DependencyIntelligenceService dependencyIntelligenceService;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public ExecutionEngine(
            ExecutionStore store,
            IncidentRepository incidents,
            ExecutionEvents events,
            NodeExecutorRegistry executors,
            NodeRegistry nodeRegistry,
            VariableInterpolator interpolator,
            ObjectMapper mapper,
            ExecutionProperties properties,
            CredentialCipher cipher,
            NotificationService notifications,
            TelemetryCollector telemetry,
            ReliabilityDetectionService detection,
            NotificationProviderRegistry notificationProviders,
            WebhookEventDispatcher webhookEvents,
            NodeIdempotencyService idempotencyService,
            CircuitBreakerService circuitBreakerService,
            RetryPolicyClassifier retryClassifier,
            DeadLetterService deadLetterService,
            DependencyIntelligenceService dependencyIntelligenceService) {
        this.store = store;
        this.incidents = incidents;
        this.events = events;
        this.executors = executors;
        this.nodeRegistry = nodeRegistry;
        this.interpolator = interpolator;
        this.mapper = mapper;
        this.properties = properties;
        this.cipher = cipher;
        this.notifications = notifications;
        this.telemetry = telemetry;
        this.detection = detection;
        this.notificationProviders = notificationProviders;
        this.webhookEvents = webhookEvents;
        this.idempotencyService = idempotencyService;
        this.circuitBreakerService = circuitBreakerService;
        this.retryClassifier = retryClassifier;
        this.deadLetterService = deadLetterService;
        this.dependencyIntelligenceService = dependencyIntelligenceService;
    }

    public void run(UUID executionId) {
        WorkflowExecution execution = store.getExecution(executionId);
        if (execution.getStatus().isTerminal()) {
            return;
        }

        GraphDocument graph;
        try {
            graph = mapper.treeToValue(
                    store.getGraph(execution.getWorkflowVersionId()),
                    GraphDocument.class);
        } catch (Exception badGraph) {
            log.error("Execution {} has an unreadable graph", executionId, badGraph);
            fail(execution, "The workflow graph could not be read.", new AtomicInteger(0));
            return;
        }

        Map<String, ExecutionNode> rows = new LinkedHashMap<>();
        for (ExecutionNode node : store.getNodes(executionId)) {
            rows.put(node.getNodeId(), node);
        }

        Map<String, List<GraphEdge>> inbound = indexInbound(graph);
        AtomicInteger seq = new AtomicInteger(store.logCount(executionId));

        boolean fresh = rows.values().stream()
                .allMatch(n -> n.getStatus() == NodeRunStatus.PENDING);

        execution.markRunning();
        execution.assignWorker(workerId);
        execution.recordHeartbeat();
        store.saveExecution(execution);
        emitExecution(execution);

        logRun(
                executionId,
                seq,
                LogLevel.INFO,
                fresh ? "Execution started." : "Execution resumed.");

        if (fresh) {
            webhookEvents.dispatch(
                    execution.getOrganizationId(),
                    WebhookEventDispatcher.Event.EXECUTION_STARTED,
                    executionPayload(execution, null));
        }

        boolean progressed;
        int executedSteps = 0;

        do {
            progressed = false;

            for (GraphNode node : graph.nodes()) {
                WorkflowExecution current = store.getExecution(executionId);

                if (current.getStatus() == ExecutionStatus.CANCELED
                        || current.getStatus().isTerminal()) {
                    return;
                }

                ExecutionNode row = rows.get(node.id());

                if (row == null || row.getStatus() != NodeRunStatus.PENDING) {
                    continue;
                }

                List<GraphEdge> edges = inbound.getOrDefault(node.id(), List.of());

                if (!isTrigger(node)) {
                    if (!allResolved(edges, rows)) {
                        continue;
                    }

                    if (!anyActive(edges, rows)) {
                        skip(executionId, row, seq);
                        progressed = true;
                        continue;
                    }
                }

                executedSteps++;
                if (executedSteps > properties.maxStepsPerExecution()) {
                    fail(
                            execution,
                            "Runaway execution loop detected: exceeded maximum steps limit ("
                                    + properties.maxStepsPerExecution() + ")",
                            seq);
                    return;
                }

                store.recordHeartbeat(executionId, workerId);
                NodeResult.Kind outcome = runNode(execution, node, row, rows, seq);

                progressed = true;

                if (outcome == NodeResult.Kind.WAIT) {
                    suspend(execution, seq);
                    return;
                }

                if (outcome == NodeResult.Kind.FAIL) {
                    fail(
                            execution,
                            "Step \"" + label(node, row)
                                    + "\" failed: " + safe(row.getError()),
                            seq);
                    return;
                }
            }
        } while (progressed);

        succeed(execution, seq);
    }

    public void abort(UUID executionId, String reason) {
        WorkflowExecution execution;

        try {
            execution = store.getExecution(executionId);
        } catch (RuntimeException gone) {
            return;
        }

        if (execution.getStatus().isTerminal()) {
            return;
        }

        for (ExecutionNode node : store.getNodes(executionId)) {
            if (node.getStatus() == NodeRunStatus.RUNNING) {
                node.fail(reason);
                store.saveNode(node);
                emitNode(executionId, node);
            }
        }

        fail(
                execution,
                reason,
                new AtomicInteger(store.logCount(executionId)));
    }

    private NodeResult.Kind runNode(
            WorkflowExecution execution,
            GraphNode node,
            ExecutionNode row,
            Map<String, ExecutionNode> rows,
            AtomicInteger seq) {

        UUID executionId = execution.getId();

        Optional<NodeExecutor> executor = executors.find(node.type());

        if (executor.isEmpty()) {
            row.markRunning(mapper.createObjectNode());
            row.fail(
                    "No executor is registered for node type \""
                            + node.type() + "\".");
            store.saveNode(row);
            emitNode(executionId, row);
            return NodeResult.Kind.FAIL;
        }

        Map<String, Object> effectiveConfig;
        Map<String, String> secrets;

        try {
            effectiveConfig = effectiveConfig(execution, node);
            secrets = resolveSecrets(execution, node);
        } catch (AgentResolutionException
                | SecretResolutionException unresolved) {

            row.markRunning(mapper.createObjectNode());
            row.fail(unresolved.getMessage());
            store.saveNode(row);
            emitNode(executionId, row);
            return NodeResult.Kind.FAIL;
        }

        JsonNode variables = buildVariables(execution, rows);
        JsonNode input = snapshotInput(node, effectiveConfig, variables);
        RetryPolicyClassifier.NodeRetryPolicy policy = retryClassifier.resolvePolicy(node, properties);
        int maxAttempts = policy.maxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            row.markRunning(input);
            store.saveNode(row);
            emitNode(executionId, row);
            store.recordHeartbeat(executionId, workerId);

            NodeLogger logger = nodeLogger(executionId, node.id(), seq);

            NodeExecutionContext ctx = new NodeExecutionContext(
                    node,
                    effectiveConfig,
                    secrets,
                    variables,
                    interpolator,
                    mapper,
                    properties,
                    logger,
                    execution.getOrganizationId(),
                    execution.getId(),
                    attempt,
                    idempotencyService,
                    circuitBreakerService);

            NodeExecutionAttempt attemptRecord = NodeExecutionAttempt.create(executionId, node.id(), attempt);

            NodeResult result;

            try {
                result = executor.get().execute(ctx);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result = NodeResult.fail("The run was interrupted.");
            } catch (Exception failure) {
                result = NodeResult.fail(sanitize(failure));
            }

            switch (result.kind()) {
                case SUCCESS -> {
                    attemptRecord.succeed();
                    store.saveAttempt(attemptRecord);

                    row.succeed(
                            result.output(),
                            fired(node, result.handles()));
                    store.saveNode(row);
                    emitNode(executionId, row);

                    if ("incident_creator".equals(node.type())
                            && result.output() != null
                            && result.output().isObject()) {

                        JsonNode output = result.output();

                        String title = output.path("title").asText("Incident");
                        String severityText = output.path("severity").asText("MEDIUM");

                        AnomalySeverity severity;
                        try {
                            severity = AnomalySeverity.valueOf(
                                    severityText.trim().toUpperCase());
                        } catch (IllegalArgumentException invalidSeverity) {
                            severity = AnomalySeverity.MEDIUM;
                        }

                        String body = output.path("body").asText("");

                        Incident incident = Incident.open(
                                execution.getOrganizationId(),
                                execution.getWorkflowId(),
                                executionId,
                                null,
                                title,
                                body,
                                severity,
                                Map.of(
                                        "notificationChannel",
                                        output.path("notificationChannel").asText(""),
                                        "nodeId",
                                        node.id()));

                        incidents.save(incident);
                    }

                    return NodeResult.Kind.SUCCESS;
                }

                case WAIT -> {
                    attemptRecord.succeed();
                    store.saveAttempt(attemptRecord);

                    row.markWaiting();
                    store.saveNode(row);
                    emitNode(executionId, row);
                    return NodeResult.Kind.WAIT;
                }

                case FAIL -> {
                    attemptRecord.fail(safe(result.error()));
                    store.saveAttempt(attemptRecord);

                    String dep = dependencyIntelligenceService.resolveDependency(node.type(), node.config());
                    if (dep != null) {
                        dependencyIntelligenceService.recordFailure(
                                execution.getOrganizationId(),
                                execution.getWorkflowId(),
                                execution.getId(),
                                dep,
                                safe(result.error()));
                    }

                    boolean retryable = retryClassifier.isRetryable(result.error(), null);
                    boolean lastAttempt = attempt >= maxAttempts
                            || !retryable
                            || Thread.currentThread().isInterrupted();

                    if (lastAttempt) {
                        row.fail(safe(result.error()));
                        store.saveNode(row);
                        emitNode(executionId, row);
                        return NodeResult.Kind.FAIL;
                    }

                    long backoff = retryClassifier.calculateBackoffMillis(attempt, policy);

                    logger.warn(
                            "Attempt " + attempt
                                    + " failed (" + (retryable ? "retryable" : "non-retryable") + "): " + safe(result.error())
                                    + " Retrying in "
                                    + (backoff / 1000.0) + "s.");

                    if (!sleep(backoff)) {
                        row.fail("The run was interrupted.");
                        store.saveNode(row);
                        emitNode(executionId, row);
                        return NodeResult.Kind.FAIL;
                    }
                }
            }
        }

        return NodeResult.Kind.FAIL;
    }

    private void skip(
            UUID executionId,
            ExecutionNode row,
            AtomicInteger seq) {

        row.skip();
        store.saveNode(row);
        emitNode(executionId, row);

        logRun(
                executionId,
                seq,
                LogLevel.DEBUG,
                "Skipped \""
                        + (row.getLabel() == null
                                ? row.getNodeId()
                                : row.getLabel())
                        + "\" — its branch was not taken.");
    }

    private Map<String, List<GraphEdge>> indexInbound(
            GraphDocument graph) {

        Map<String, List<GraphEdge>> inbound = new LinkedHashMap<>();

        for (GraphEdge edge : graph.edges()) {
            if (edge.target() != null) {
                inbound
                        .computeIfAbsent(edge.target(), t -> new ArrayList<>())
                        .add(edge);
            }
        }

        return inbound;
    }

    private boolean isTrigger(GraphNode node) {
        return nodeRegistry
                .find(node.type())
                .map(NodeDefinition::trigger)
                .orElse(false);
    }

    private boolean allResolved(
            List<GraphEdge> inbound,
            Map<String, ExecutionNode> rows) {

        for (GraphEdge edge : inbound) {
            ExecutionNode source = rows.get(edge.source());

            if (source == null || !source.getStatus().isResolved()) {
                return false;
            }
        }

        return true;
    }

    private boolean anyActive(
            List<GraphEdge> inbound,
            Map<String, ExecutionNode> rows) {

        for (GraphEdge edge : inbound) {
            if (isEdgeActive(edge, rows)) {
                return true;
            }
        }

        return false;
    }

    private boolean isEdgeActive(
            GraphEdge edge,
            Map<String, ExecutionNode> rows) {

        ExecutionNode source = rows.get(edge.source());

        if (source == null
                || source.getStatus() != NodeRunStatus.SUCCEEDED) {
            return false;
        }

        String handle = edge.sourceHandle();

        return handle == null
                || handle.isBlank()
                || source.activeHandles().contains(handle);
    }

    private List<String> fired(
            GraphNode node,
            List<String> handles) {

        if (handles != null && !handles.isEmpty()) {
            return handles;
        }

        return nodeRegistry
                .find(node.type())
                .map(NodeDefinition::outputs)
                .filter(outputs -> !outputs.isEmpty())
                .orElse(List.of("out"));
    }

    private JsonNode buildVariables(
            WorkflowExecution execution,
            Map<String, ExecutionNode> rows) {

        ObjectNode root = mapper.createObjectNode();

        JsonNode trigger = execution.getTriggerPayload();

        root.set(
                "trigger",
                trigger == null || trigger.isNull()
                        ? mapper.createObjectNode()
                        : trigger);

        for (ExecutionNode row : rows.values()) {
            if (row.getStatus() == NodeRunStatus.SUCCEEDED
                    && row.getOutput() != null) {
                root.set(row.getNodeId(), row.getOutput());
            }
        }

        for (ExecutionNode row : rows.values()) {
            if (row.getStatus() == NodeRunStatus.SUCCEEDED
                    && row.getOutput() != null
                    && !root.has(row.getNodeType())) {
                root.set(row.getNodeType(), row.getOutput());
            }
        }

        return root;
    }

    private JsonNode snapshotInput(
            GraphNode node,
            Map<String, Object> config,
            JsonNode variables) {

        if (isTrigger(node)) {
            JsonNode trigger = variables.get("trigger");
            return trigger == null
                    ? mapper.createObjectNode()
                    : trigger.deepCopy();
        }

        ObjectNode snapshot = mapper.createObjectNode();

        config.forEach((key, value) -> {
            if (value instanceof String text) {
                snapshot.put(
                        key,
                        interpolator.interpolate(text, variables));
            } else {
                snapshot.set(
                        key,
                        mapper.valueToTree(value));
            }
        });

        return snapshot;
    }

    private Map<String, Object> effectiveConfig(
            WorkflowExecution execution,
            GraphNode node) {

        Map<String, Object> base = node.config();

        if (!"ai_agent".equals(node.type())) {
            return base;
        }

        Object rawId = base.get("agentId");
        String agentId = rawId == null
                ? ""
                : String.valueOf(rawId).strip();

        if (agentId.isBlank()) {
            return base;
        }

        UUID agentUuid;

        try {
            agentUuid = UUID.fromString(agentId);
        } catch (IllegalArgumentException badId) {
            throw new AgentResolutionException(
                    "The AI agent this node references is invalid.");
        }

        AiAgent agent = store.findAiAgentForRun(
                execution.getOrganizationId(),
                agentUuid)
                .orElseThrow(
                        () -> new AgentResolutionException(
                                "The AI agent this node references was not found."));

        Map<String, Object> merged = new LinkedHashMap<>(base);

        merged.put("instructions", agent.getInstructions());

        if (agent.getModel() != null
                && !agent.getModel().isBlank()) {
            merged.put("model", agent.getModel());
        }

        merged.put("tools", agentToolNames(agent));

        return merged;
    }

    private List<String> agentToolNames(AiAgent agent) {
        JsonNode tools = agent.getTools();

        if (tools == null || !tools.isArray()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();

        for (JsonNode tool : tools) {
            if (tool != null && tool.isTextual()) {
                String name = tool.asText().strip();

                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }

        return names;
    }

    private static final class AgentResolutionException
            extends RuntimeException {

        AgentResolutionException(String message) {
            super(message);
        }
    }

    private static final java.util.Set<String> CREDENTIALED_NODE_TYPES = java.util.Set.of(
            "notification",
            "slack",
            "discord",
            "teams",
            "email",
            "telegram",
            "twilio",
            "gmail",
            "google_sheets",
            "google_sheets_append_row",
            "github",
            "github_create_issue",
            "jira",
            "notion",
            "notion_create_page",
            "linear_create_issue",
            "salesforce_create_lead",
            "pagerduty_trigger_incident",
            "s3_upload",
            "hubspot",
            "stripe",
            "openai",
            "outbound_webhook",
            "incident_creator",
            "escalation",
            "http_request");

    private Map<String, String> resolveSecrets(
            WorkflowExecution execution,
            GraphNode node) {

        if (!CREDENTIALED_NODE_TYPES.contains(node.type())) {
            return Map.of();
        }

        Object rawChannel = node.config().get("channel");
        String channel = rawChannel != null ? String.valueOf(rawChannel).strip() : "";
        IntegrationType type = IntegrationType.fromWire(channel);

        if (type == null) {
            type = determineIntegrationTypeFromNode(node.type());
        }

        if (type == null && !"http_request".equals(node.type()) && !"outbound_webhook".equals(node.type())) {
            return Map.of();
        }

        UUID organizationId = execution.getOrganizationId();

        Object rawIntegrationId = node.config().get("integrationId");

        if (rawIntegrationId != null
                && !String.valueOf(rawIntegrationId).isBlank()) {

            UUID integrationId;

            try {
                integrationId = UUID.fromString(
                        String.valueOf(rawIntegrationId).strip());
            } catch (IllegalArgumentException notAUuid) {
                return Map.of();
            }

            Optional<ExecutionStore.IntegrationWithCredential> resolved = store.findConnectedIntegration(
                    organizationId,
                    integrationId);

            if (resolved.isEmpty()) {
                return Map.of();
            }

            Integration integration = resolved.get().integration();

            if (!type.wire()
                    .equalsIgnoreCase(integration.getType())) {

                throw new SecretResolutionException(
                        "The referenced integration is "
                                + integration.getType()
                                + ", not "
                                + type.wire()
                                + ".");
            }

            try {
                return decryptCredentials(
                        type,
                        resolved.get()
                                .credential()
                                .getCiphertext());
            } catch (RuntimeException undecryptable) {
                throw new SecretResolutionException(
                        "A stored "
                                + type.wire()
                                + " credential could not be read.");
            }
        }

        /*
         * Backward-compatible Slack fallback.
         *
         * New Slack connections are encrypted as a JSON map:
         * {"webhookUrl":"https://hooks.slack.com/..."}
         *
         * Therefore we must decrypt using decryptToMap(), rather than
         * cipher.decrypt(), which would return the whole JSON document as
         * a String and cause the HTTP client to interpret the JSON as a URL.
         */
        /*
         * Backward-compatible Slack fallback.
         *
         * New Slack connections are encrypted as a JSON map:
         * {"webhookUrl":"https://hooks.slack.com/..."}
         *
         * Therefore we must decrypt using decryptToMap(), rather than
         * cipher.decrypt(), which would return the whole JSON document as
         * a String and cause the HTTP client to interpret the JSON as a URL.
         */
        if (type == IntegrationType.SLACK) {
            Optional<IntegrationCredential> credential = store.findConnectedSlackCredential(
                    organizationId);

            if (credential.isEmpty()) {
                return Map.of();
            }

            try {
                return decryptCredentials(
                        IntegrationType.SLACK,
                        credential.get().getCiphertext());
            } catch (RuntimeException undecryptable) {
                throw new SecretResolutionException(
                        "A stored Slack credential could not be read.");
            }
        }

        return Map.of();
    }

    private IntegrationType determineIntegrationTypeFromNode(String nodeType) {
        if (nodeType == null) return null;

        if (nodeType.startsWith("slack")) return IntegrationType.SLACK;
        if (nodeType.startsWith("discord")) return IntegrationType.DISCORD;
        if (nodeType.startsWith("teams")) return IntegrationType.TEAMS;
        if (nodeType.startsWith("telegram")) return IntegrationType.TELEGRAM;
        if (nodeType.startsWith("twilio")) return IntegrationType.TWILIO;
        if (nodeType.startsWith("email")) return IntegrationType.EMAIL;
        if (nodeType.startsWith("gmail")) return IntegrationType.EMAIL;
        if (nodeType.startsWith("google_sheets")) return IntegrationType.GOOGLE_SHEETS;
        if (nodeType.startsWith("github")) return IntegrationType.GITHUB;
        if (nodeType.startsWith("jira")) return IntegrationType.JIRA;
        if (nodeType.startsWith("notion")) return IntegrationType.NOTION;
        if (nodeType.startsWith("linear")) return IntegrationType.LINEAR;
        if (nodeType.startsWith("salesforce")) return IntegrationType.SALESFORCE;
        if (nodeType.startsWith("pagerduty")) return IntegrationType.PAGERDUTY;
        if (nodeType.startsWith("s3")) return IntegrationType.S3;
        if (nodeType.startsWith("hubspot")) return IntegrationType.HUBSPOT;
        if (nodeType.startsWith("stripe")) return IntegrationType.STRIPE;
        if (nodeType.startsWith("openai")) return IntegrationType.OPENAI;
        return null;
    }

    private Map<String, String> decryptCredentials(
        IntegrationType type,
        String ciphertext) {

    Map<String, String> decrypted = cipher.decryptToMap(ciphertext);

    if (decrypted == null || decrypted.isEmpty()) {
        return Map.of();
    }

    return decrypted;
}

private static final class SecretResolutionException
        extends RuntimeException {

    SecretResolutionException(String message) {
        super(message);
    }
}

    private void succeed(
            WorkflowExecution execution,
            AtomicInteger seq) {

        execution.markSucceeded();
        store.saveExecution(execution);
        emitExecution(execution);

        logRun(
                execution.getId(),
                seq,
                LogLevel.INFO,
                "Execution succeeded.");

        webhookEvents.dispatch(
                execution.getOrganizationId(),
                WebhookEventDispatcher.Event.EXECUTION_COMPLETED,
                executionPayload(execution, null));

        captureTelemetry(execution.getId());
        detectReliability(execution.getId());
        events.complete(execution.getId());
    }

    private void fail(
            WorkflowExecution execution,
            String message,
            AtomicInteger seq) {

        execution.markFailed(safe(message));
        store.saveExecution(execution);
        emitExecution(execution);

        logRun(
                execution.getId(),
                seq,
                LogLevel.ERROR,
                safe(message));

        try {
            List<ExecutionNode> allNodes = store.getNodes(execution.getId());
            ExecutionNode failedNode = allNodes.stream()
                    .filter(n -> n.getStatus() == NodeRunStatus.FAILED)
                    .findFirst()
                    .orElse(null);

            String failedNodeId = failedNode != null ? failedNode.getNodeId() : "workflow";
            String failedNodeType = failedNode != null ? failedNode.getNodeType() : "workflow";
            int attempts = failedNode != null ? failedNode.getAttempt() : 1;

            ObjectNode executionContext = mapper.createObjectNode();
            executionContext.put("failureReason", safe(message));
            executionContext.put("totalNodes", allNodes.size());
            if (failedNode != null && failedNode.getInput() != null) {
                executionContext.set("failedNodeInput", failedNode.getInput());
            }

            deadLetterService.capture(
                    execution,
                    failedNodeId,
                    failedNodeType,
                    attempts,
                    safe(message),
                    executionContext);
        } catch (Exception dlEx) {
            log.warn("Could not capture dead letter record for execution {}: {}",
                    execution.getId(), dlEx.getMessage());
        }

        webhookEvents.dispatch(
                execution.getOrganizationId(),
                WebhookEventDispatcher.Event.EXECUTION_FAILED,
                executionPayload(execution, safe(message)));

        notify(
                execution,
                NotificationLevel.ERROR,
                "A workflow run failed",
                safe(message));

        captureTelemetry(execution.getId());
        detectReliability(execution.getId());
        events.complete(execution.getId());
    }

    private static Map<String, Object> executionPayload(
            WorkflowExecution execution,
            String error) {

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put(
                "workflowId",
                execution.getWorkflowId().toString());

        payload.put(
                "workflowVersionId",
                execution.getWorkflowVersionId().toString());

        payload.put(
                "version",
                execution.getVersionNumber());

        payload.put(
                "executionId",
                execution.getId().toString());

        payload.put(
                "status",
                execution.getStatus().name().toLowerCase());

        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }

        return payload;
    }

    private void captureTelemetry(UUID executionId) {
        try {
            telemetry.capture(executionId);
        } catch (RuntimeException telemetryFailure) {
            log.warn(
                    "Could not capture reliability telemetry for execution {}",
                    executionId,
                    telemetryFailure);
        }
    }

    private void detectReliability(UUID executionId) {
        try {
            detection.detectInExecution(executionId);
        } catch (RuntimeException detectionFailure) {
            log.warn(
                    "Could not run reliability detection for execution {}",
                    executionId,
                    detectionFailure);
        }
    }

    private void suspend(
            WorkflowExecution execution,
            AtomicInteger seq) {

        execution.markWaiting();
        store.saveExecution(execution);
        emitExecution(execution);

        logRun(
                execution.getId(),
                seq,
                LogLevel.INFO,
                "Execution is waiting for a human decision.");

        notify(
                execution,
                NotificationLevel.WARN,
                "A workflow run needs your approval",
                "The run is paused until someone approves or rejects the step.");

        events.complete(execution.getId());
    }

    private void notify(
            WorkflowExecution execution,
            NotificationLevel level,
            String title,
            String body) {

        try {
            notifications.publish(
                    execution.getOrganizationId(),
                    execution.getCreatedBy(),
                    level,
                    title,
                    body,
                    "/executions/" + execution.getId());
        } catch (RuntimeException notifyFailure) {
            log.warn(
                    "Could not publish a notification for execution {}",
                    execution.getId());
        }
    }

    private NodeLogger nodeLogger(
            UUID executionId,
            String nodeId,
            AtomicInteger seq) {

        return new NodeLogger() {
            @Override
            public void debug(String message) {
                write(LogLevel.DEBUG, message);
            }

            @Override
            public void info(String message) {
                write(LogLevel.INFO, message);
            }

            @Override
            public void warn(String message) {
                write(LogLevel.WARN, message);
            }

            @Override
            public void error(String message) {
                write(LogLevel.ERROR, message);
            }

            private void write(
                    LogLevel level,
                    String message) {

                ExecutionLog saved = store.appendLog(
                        executionId,
                        nodeId,
                        level,
                        safe(message),
                        seq.getAndIncrement());

                events.emit(
                        executionId,
                        ExecutionEvents.LOG,
                        ExecutionLogResponse.of(saved));
            }
        };
    }

    private void logRun(
            UUID executionId,
            AtomicInteger seq,
            LogLevel level,
            String message) {

        ExecutionLog saved = store.appendLog(
                executionId,
                null,
                level,
                safe(message),
                seq.getAndIncrement());

        events.emit(
                executionId,
                ExecutionEvents.LOG,
                ExecutionLogResponse.of(saved));
    }

    private void emitNode(
            UUID executionId,
            ExecutionNode node) {

        events.emit(
                executionId,
                ExecutionEvents.NODE,
                ExecutionNodeResponse.of(node));
    }

    private void emitExecution(
            WorkflowExecution execution) {

        events.emit(
                execution.getId(),
                ExecutionEvents.EXECUTION,
                ExecutionSummaryResponse.of(
                        execution,
                        null));
    }

    private long backoffMillis(int attempt) {
        long base = properties.retryBackoff().toMillis();

        long scaled = base * (1L << (attempt - 1));

        return Math.min(
                scaled,
                properties.maxRetryBackoff().toMillis());
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }

        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String label(
            GraphNode node,
            ExecutionNode row) {

        if (row.getLabel() != null
                && !row.getLabel().isBlank()) {
            return row.getLabel();
        }

        return node.id();
    }

    private String safe(String message) {
        if (message == null || message.isBlank()) {
            return "Unexpected error.";
        }

        String cleaned = message.replaceAll("\\s+", " ").trim();

        return cleaned.length() > 500
                ? cleaned.substring(0, 500) + "…"
                : cleaned;
    }

    private String sanitize(Exception failure) {
        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message;
    }
}