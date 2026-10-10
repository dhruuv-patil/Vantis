package com.flowops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.GenerateWorkflowResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.workflow.nodes.ConfigField;
import com.flowops.workflow.nodes.NodeDefinition;
import com.flowops.workflow.nodes.NodeRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Create with AI": proxies a natural-language prompt to the AI service and returns a
 * builder-ready workflow graph (M4).
 *
 * <p>The model's output is never trusted verbatim. Every node is re-validated against
 * the {@link NodeRegistry}: unknown types are dropped, config keys the type does not
 * declare are stripped, positions are assigned so nothing stacks at (0,0), and edges
 * survive only between surviving nodes. The result is only ever placed on the canvas —
 * it is never auto-run (contract: require explicit user confirmation).
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI")
public class AiController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiController.class);

    private final AiServiceClient aiService;
    private final NodeRegistry nodeRegistry;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final com.flowops.billing.service.EntitlementService entitlementService;
    private final com.flowops.workflow.validation.WorkflowValidator validator;

    @org.springframework.beans.factory.annotation.Autowired
    public AiController(
            AiServiceClient aiService,
            NodeRegistry nodeRegistry,
            ObjectMapper mapper,
            RateLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.billing.service.EntitlementService entitlementService,
            com.flowops.workflow.validation.WorkflowValidator validator) {
        this.aiService = aiService;
        this.nodeRegistry = nodeRegistry;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.entitlementService = entitlementService;
        this.validator = validator != null ? validator : new com.flowops.workflow.validation.WorkflowValidator(nodeRegistry, mapper);
    }

    public AiController(
            AiServiceClient aiService,
            NodeRegistry nodeRegistry,
            ObjectMapper mapper,
            RateLimiter rateLimiter,
            com.flowops.billing.service.EntitlementService entitlementService) {
        this(aiService, nodeRegistry, mapper, rateLimiter, entitlementService, null);
    }

    public AiController(
            AiServiceClient aiService,
            NodeRegistry nodeRegistry,
            ObjectMapper mapper,
            RateLimiter rateLimiter) {
        this(aiService, nodeRegistry, mapper, rateLimiter, (com.flowops.billing.service.EntitlementService) null, null);
    }

    @PostMapping(path = "/generate-workflow", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Turn a natural-language prompt into a draft workflow graph")
    public GenerateWorkflowResponse generate(
            @Valid @RequestBody GenerateWorkflowRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        // Authenticated tenant context is required even though generation is stateless.
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        rateLimiter.check("ai:generate:" + principal.userId(), 20, Duration.ofMinutes(1));

        com.flowops.billing.model.ExecutionReservationTicket ticket = com.flowops.billing.model.ExecutionReservationTicket.notReserved();
        if (entitlementService != null) {
            ticket = entitlementService.reserveAiGeneration(principal.organizationId());
        }

        boolean succeeded = false;
        try {
            String requestId = com.flowops.common.error.RequestIdFilter.currentRequestId(servletRequest);
            log.info("Starting workflow generation requestId={} promptLength={}",
                    requestId, request.prompt() != null ? request.prompt().length() : 0);

            AiServiceClient.Generation generation = aiService.generateWorkflow(request.prompt());

            if (!generation.configured()) {
                log.info("AI service reported not configured. requestId={}", requestId);
                throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
            }

            if (generation.graph() == null || generation.graph().isNull()) {
                log.warn("AI service returned empty/null graph. requestId={}", requestId);
                throw new ApiException(ErrorCode.AI_GENERATION_FAILED);
            }

            // Reshape and position graph for the canvas
            ObjectNode graph = sanitizeGraph(generation.graph());
            if (!graph.hasNonNull("nodes") || graph.get("nodes").isEmpty()) {
                log.warn("Sanitized graph produced 0 valid nodes. requestId={}", requestId);
                throw new ApiException(ErrorCode.AI_GENERATION_FAILED);
            }

            log.info("Workflow generation succeeded: nodes={} edges={} requestId={}",
                    graph.get("nodes").size(), graph.get("edges").size(), requestId);

            GenerateWorkflowResponse response = new GenerateWorkflowResponse(graph, generation.notes());
            succeeded = true;
            return response;
        } finally {
            if (!succeeded && ticket.reserved() && entitlementService != null) {
                entitlementService.refundAiGeneration(principal.organizationId(), ticket);
            }
        }
    }

    public GenerateWorkflowResponse generate(GenerateWorkflowRequest request) {
        return generate(request, null);
    }

    /**
     * Re-shapes the model's raw {@code {nodes,edges}} into the persisted graph shape
     * the builder round-trips ({@code data.config}, {@code position}), validating each
     * node against the registry along the way.
     */
    private ObjectNode sanitizeGraph(JsonNode raw) {
        ObjectNode graph = mapper.createObjectNode();
        ArrayNode outNodes = graph.putArray("nodes");
        ArrayNode outEdges = graph.putArray("edges");
        if (raw == null || !raw.isObject()) {
            return graph;
        }

        Set<String> ids = new LinkedHashSet<>();
        JsonNode nodes = raw.get("nodes");
        int index = 0;
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                String id = text(node, "id");
                String type = text(node, "type");
                if (id == null || id.isBlank() || type == null) {
                    continue;
                }
                Optional<NodeDefinition> definition = nodeRegistry.find(type);
                if (definition.isEmpty() || !ids.add(id)) {
                    continue; // unknown type, or a duplicate id
                }

                ObjectNode outNode = outNodes.addObject();
                outNode.put("id", id);
                outNode.put("type", type);
                ObjectNode position = outNode.putObject("position");
                if (node.has("position") && node.get("position").isObject()
                        && node.get("position").has("x") && node.get("position").has("y")) {
                    position.set("x", node.get("position").get("x"));
                    position.set("y", node.get("position").get("y"));
                } else {
                    position.put("x", 160);
                    position.put("y", 80 + index * 140);
                }
                ObjectNode data = outNode.putObject("data");
                String label = text(node, "label");
                if (label == null || label.isBlank()) {
                    label = node.has("data") ? text(node.get("data"), "label") : null;
                }
                data.put("label", label == null || label.isBlank() ? definition.get().label() : label);
                JsonNode rawConfig = node.has("data") && node.get("data").has("config")
                        ? node.get("data").get("config")
                        : node.get("config");
                data.set("config", sanitizeConfig(definition.get(), rawConfig));
                index++;
            }
        }

        JsonNode edges = raw.get("edges");
        if (edges != null && edges.isArray()) {
            int seq = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode edge : edges) {
                String source = text(edge, "source");
                String target = text(edge, "target");
                if (source == null || target == null || !ids.contains(source) || !ids.contains(target)) {
                    continue;
                }
                String handle = text(edge, "sourceHandle");
                if (handle == null) {
                    handle = text(edge, "source_handle");
                }
                String key = source + "->" + target + "#" + (handle == null ? "" : handle);
                if (!seen.add(key)) {
                    continue;
                }
                ObjectNode outEdge = outEdges.addObject();
                String edgeId = text(edge, "id");
                outEdge.put("id", edgeId != null && !edgeId.isBlank() ? edgeId : ("e" + (seq++) + "-" + source + "-" + target));
                outEdge.put("source", source);
                outEdge.put("target", target);
                if (handle != null && !handle.isBlank()) {
                    outEdge.put("sourceHandle", handle);
                }
            }
        }
        return graph;
    }

    /** Keeps only config keys the node type actually declares. */
    private ObjectNode sanitizeConfig(NodeDefinition definition, JsonNode rawConfig) {
        ObjectNode config = mapper.createObjectNode();
        if (rawConfig == null || !rawConfig.isObject()) {
            return config;
        }
        for (ConfigField field : definition.configFields()) {
            JsonNode value = rawConfig.get(field.key());
            if (value != null && !value.isNull()) {
                config.set(field.key(), value.deepCopy());
            }
        }
        return config;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
