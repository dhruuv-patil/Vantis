package com.flowops.workflow;

import com.flowops.api.ExecutionDetailResponse;
import com.flowops.api.WorkflowDetailResponse;
import com.flowops.api.WorkflowEnvelopes;
import com.flowops.api.WorkflowVersionResponse;
import com.flowops.domain.WorkflowStatus;
import com.flowops.execution.ExecutionService;
import com.flowops.execution.RunWorkflowRequest;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.workflow.validation.ValidationResult;
import com.flowops.common.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow CRUD, draft save, validation, and publishing (M2).
 *
 * <p>The tenant is always the caller's current organization, read from the
 * principal by {@link AuthenticatedUser}. No endpoint accepts an organization id.
 */
@RestController
@RequestMapping("/api/workflows")
@Tag(name = "Workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ExecutionService executionService;
    private final RateLimiter rateLimiter;

    public WorkflowController(
            WorkflowService workflowService,
            ExecutionService executionService,
            RateLimiter rateLimiter) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    @Operation(summary = "List workflows in the current organization")
    public WorkflowEnvelopes.Workflows list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) WorkflowStatus status) {
        return workflowService.list(AuthenticatedUser.require(), search, status);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft workflow")
    public WorkflowDetailResponse create(@Valid @RequestBody CreateWorkflowRequest request) {
        return workflowService.create(AuthenticatedUser.requireRole(Role.MEMBER), request);
    }

    @GetMapping("/{workflowId}")
    @Operation(summary = "Get a workflow with its draft graph")
    public WorkflowDetailResponse get(@PathVariable UUID workflowId) {
        return workflowService.get(AuthenticatedUser.require(), workflowId);
    }

    @PatchMapping(path = "/{workflowId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename or re-describe a workflow")
    public WorkflowDetailResponse update(
            @PathVariable UUID workflowId, @Valid @RequestBody UpdateWorkflowRequest request) {
        return workflowService.updateMetadata(AuthenticatedUser.requireRole(Role.MEMBER), workflowId, request);
    }

    @PutMapping(path = "/{workflowId}/graph", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save the working (draft) graph")
    public WorkflowDetailResponse saveGraph(
            @PathVariable UUID workflowId, @Valid @RequestBody SaveGraphRequest request) {
        return workflowService.saveDraft(AuthenticatedUser.requireRole(Role.MEMBER), workflowId, request);
    }

    @PostMapping("/{workflowId}/validate")
    @Operation(summary = "Validate the draft graph without publishing")
    public ValidationResult validate(@PathVariable UUID workflowId) {
        return workflowService.validate(AuthenticatedUser.requireRole(Role.MEMBER), workflowId);
    }

    @PostMapping("/{workflowId}/publish")
    @Operation(summary = "Validate and, if clean, publish an immutable version")
    public WorkflowEnvelopes.PublishResult publish(
            @PathVariable UUID workflowId,
            @Valid @RequestBody(required = false) PublishWorkflowRequest request) {
        PublishWorkflowRequest body = request == null ? new PublishWorkflowRequest(null) : request;
        return workflowService.publish(AuthenticatedUser.requireRole(Role.MEMBER), workflowId, body);
    }

    @PostMapping("/{workflowId}/run")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a run of the workflow's latest published version")
    public ExecutionDetailResponse run(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) RunWorkflowRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        rateLimiter.check("workflow:run:" + principal.userId(), 60, Duration.ofMinutes(1));
        return executionService.run(principal, workflowId, request);
    }

    @GetMapping("/{workflowId}/versions")
    @Operation(summary = "List a workflow's published versions")
    public WorkflowEnvelopes.Versions versions(@PathVariable UUID workflowId) {
        return workflowService.listVersions(AuthenticatedUser.require(), workflowId);
    }

    @GetMapping("/{workflowId}/versions/{versionNumber}")
    @Operation(summary = "Get a specific published version including its graph")
    public WorkflowVersionResponse version(
            @PathVariable UUID workflowId, @PathVariable int versionNumber) {
        return workflowService.getVersion(AuthenticatedUser.require(), workflowId, versionNumber);
    }

    @DeleteMapping("/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a workflow and its versions")
    public ResponseEntity<Void> delete(@PathVariable UUID workflowId) {
        workflowService.delete(AuthenticatedUser.requireRole(Role.MEMBER), workflowId);
        return ResponseEntity.noContent().build();
    }
}
