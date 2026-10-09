package com.flowops.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.WorkflowDetailResponse;
import com.flowops.api.WorkflowEnvelopes;
import com.flowops.api.WorkflowSummaryResponse;
import com.flowops.api.WorkflowVersionResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStatus;
import com.flowops.domain.WorkflowVersion;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.workflow.validation.ValidationResult;
import com.flowops.workflow.validation.WorkflowValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow lifecycle: create, edit metadata, save the working graph, validate,
 * and publish immutable versions.
 *
 * <p>Every read and write is scoped to the caller's organization (taken from the
 * principal, never the request body). A workflow belonging to another tenant is
 * indistinguishable from one that does not exist — both yield
 * {@link ErrorCode#WORKFLOW_NOT_FOUND} (contract §2, anti-enumeration).
 */
@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowValidator validator;
    private final ObjectMapper objectMapper;
    private final WebhookEventDispatcher webhookEvents;
    private final com.flowops.billing.service.EntitlementService entitlementService;
    private final com.flowops.repository.OrganizationRepository organizationRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowService(
            WorkflowRepository workflows,
            WorkflowVersionRepository versions,
            WorkflowValidator validator,
            ObjectMapper objectMapper,
            WebhookEventDispatcher webhookEvents,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.billing.service.EntitlementService entitlementService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.repository.OrganizationRepository organizationRepository) {
        this.workflows = workflows;
        this.versions = versions;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.webhookEvents = webhookEvents;
        this.entitlementService = entitlementService;
        this.organizationRepository = organizationRepository;
    }

    public WorkflowService(
            WorkflowRepository workflows,
            WorkflowVersionRepository versions,
            WorkflowValidator validator,
            ObjectMapper objectMapper,
            WebhookEventDispatcher webhookEvents) {
        this(workflows, versions, validator, objectMapper, webhookEvents, null, null);
    }

    @Transactional(readOnly = true)
    public WorkflowEnvelopes.Workflows list(
            FlowOpsPrincipal principal, String search, WorkflowStatus status) {
        String term = (search == null || search.isBlank()) ? null : search.strip();
        List<WorkflowSummaryResponse> rows = workflows
                .search(principal.organizationId(), term, status)
                .stream()
                .map(w -> WorkflowSummaryResponse.of(w, nodeCount(w.getDraftGraph())))
                .toList();
        return new WorkflowEnvelopes.Workflows(rows);
    }

    @Transactional
    public WorkflowDetailResponse create(FlowOpsPrincipal principal, CreateWorkflowRequest request) {
        // Draft creation is completely ungated
        Workflow workflow = Workflow.create(
                principal.organizationId(),
                principal.userId(),
                request.name(),
                request.description(),
                emptyGraph());
        workflows.save(workflow);
        return WorkflowDetailResponse.of(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowDetailResponse get(FlowOpsPrincipal principal, UUID workflowId) {
        return WorkflowDetailResponse.of(require(principal, workflowId));
    }

    @Transactional
    public WorkflowDetailResponse updateMetadata(
            FlowOpsPrincipal principal, UUID workflowId, UpdateWorkflowRequest request) {
        Workflow workflow = require(principal, workflowId);
        workflow.editMetadata(request.name(), request.description());
        return WorkflowDetailResponse.of(workflow);
    }

    @Transactional
    public WorkflowDetailResponse saveDraft(
            FlowOpsPrincipal principal, UUID workflowId, SaveGraphRequest request) {
        Workflow workflow = require(principal, workflowId);
        workflow.saveDraft(normalizeGraph(request.graph()));
        return WorkflowDetailResponse.of(workflow);
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(FlowOpsPrincipal principal, UUID workflowId) {
        Workflow workflow = require(principal, workflowId);
        return validator.validate(workflow.getDraftGraph());
    }

    /**
     * Validates the current draft and, only if there are no errors, snapshots it
     * into a new immutable {@link WorkflowVersion} and marks the workflow
     * published. On validation errors nothing is written.
     */
    @Transactional
    public WorkflowEnvelopes.PublishResult publish(
            FlowOpsPrincipal principal, UUID workflowId, PublishWorkflowRequest request) {
        Workflow workflow = require(principal, workflowId);
        ValidationResult validation = validator.validate(workflow.getDraftGraph());
        if (!validation.valid()) {
            return WorkflowEnvelopes.PublishResult.rejected(validation);
        }

        // Activation check:
        // Grandfathering: do not use latestVersion == null as a proxy.
        // Define "activation" by actual active state transition (inactive to active).
        // Republishing an already-active workflow is never blocked.
        // Reactivating an inactive workflow counts as a new activation.
        // Atomic check: lock the org row so concurrent activations serialize.
        boolean isActivating = workflow.getStatus() != WorkflowStatus.PUBLISHED;
        if (isActivating && entitlementService != null) {
            if (organizationRepository != null) {
                organizationRepository.findByIdWithLock(principal.organizationId());
            }
            entitlementService.assertCanActivateWorkflow(principal.organizationId());
        }

        int nextVersion = workflow.getLatestVersion() == null ? 1 : workflow.getLatestVersion() + 1;
        WorkflowVersion version = WorkflowVersion.create(
                workflow.getId(),
                nextVersion,
                workflow.getDraftGraph().deepCopy(),
                request.note(),
                principal.userId());
        versions.save(version);
        workflow.markPublished(nextVersion);
        // Fire outbound webhooks subscribed to workflow.published
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("workflowId", workflow.getId().toString());
        payload.put("workflowName", workflow.getName());
        payload.put("version", nextVersion);
        webhookEvents.dispatch(principal.organizationId(),
                WebhookEventDispatcher.Event.WORKFLOW_PUBLISHED, payload);
        return WorkflowEnvelopes.PublishResult.ok(
                WorkflowVersionResponse.summary(version), validation);
    }

    @Transactional(readOnly = true)
    public WorkflowEnvelopes.Versions listVersions(FlowOpsPrincipal principal, UUID workflowId) {
        require(principal, workflowId);
        List<WorkflowVersionResponse> rows = versions
                .findByWorkflowIdOrderByVersionNumberDesc(workflowId)
                .stream()
                .map(WorkflowVersionResponse::summary)
                .toList();
        return new WorkflowEnvelopes.Versions(rows);
    }

    @Transactional(readOnly = true)
    public WorkflowVersionResponse getVersion(
            FlowOpsPrincipal principal, UUID workflowId, int versionNumber) {
        require(principal, workflowId);
        WorkflowVersion version = versions
                .findByWorkflowIdAndVersionNumber(workflowId, versionNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_NOT_FOUND));
        return WorkflowVersionResponse.full(version);
    }

    @Transactional
    public void delete(FlowOpsPrincipal principal, UUID workflowId) {
        Workflow workflow = require(principal, workflowId);
        workflows.delete(workflow);
    }

    /** Fetch scoped to the caller's org, or 404. The single choke point for tenant isolation. */
    private Workflow require(FlowOpsPrincipal principal, UUID workflowId) {
        return workflows
                .findByIdAndOrganizationId(workflowId, principal.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_NOT_FOUND));
    }

    private JsonNode emptyGraph() {
        ObjectNode graph = objectMapper.createObjectNode();
        graph.set("nodes", objectMapper.createArrayNode());
        graph.set("edges", objectMapper.createArrayNode());
        return graph;
    }

    /**
     * Guarantees the stored graph always has {@code nodes} and {@code edges}
     * arrays, so downstream readers (validator, executor) never null-check the
     * top level. Non-object input is replaced with an empty graph.
     */
    private JsonNode normalizeGraph(JsonNode graph) {
        if (graph == null || !graph.isObject()) {
            return emptyGraph();
        }
        ObjectNode object = (ObjectNode) graph;
        if (!object.has("nodes") || !object.get("nodes").isArray()) {
            object.set("nodes", objectMapper.createArrayNode());
        }
        if (!object.has("edges") || !object.get("edges").isArray()) {
            object.set("edges", objectMapper.createArrayNode());
        }
        return object;
    }

    private int nodeCount(JsonNode graph) {
        JsonNode nodes = graph == null ? null : graph.get("nodes");
        return nodes instanceof ArrayNode array ? array.size() : 0;
    }
}
