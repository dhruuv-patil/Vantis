package com.flowops.execution;

import com.flowops.api.ExecutionDetailResponse;
import com.flowops.api.ExecutionEnvelopes;
import com.flowops.api.ExecutionStatsResponse;
import com.flowops.domain.ExecutionStatus;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reading and steering workflow runs (M3): the executions list, a run's detail,
 * the live SSE stream, approval decisions, and retries — plus the dashboard's
 * analytics window.
 *
 * <p>As everywhere, the tenant is the caller's organization read from the
 * principal; no endpoint accepts an organization id, and a run in another org is
 * reported as not found.
 */
@RestController
@RequestMapping("/api/executions")
@Tag(name = "Executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping
    @Operation(summary = "List runs in the current organization, newest first")
    public ExecutionEnvelopes.Executions list(
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) Integer limit) {
        return executionService.list(AuthenticatedUser.require(), workflowId, status, limit);
    }

    @GetMapping("/stats")
    @Operation(summary = "Execution KPIs and a bucketed time series for a window (24h/7d/30d/90d)")
    public ExecutionStatsResponse stats(@RequestParam(required = false, defaultValue = "7d") String range) {
        return executionService.stats(AuthenticatedUser.require(), range);
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "Get a run with its node states and log")
    public ExecutionDetailResponse get(@PathVariable UUID executionId) {
        return executionService.get(AuthenticatedUser.require(), executionId);
    }

    @GetMapping(path = "/{executionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live SSE stream of node, log, and status events for a run")
    public SseEmitter stream(@PathVariable UUID executionId) {
        return executionService.stream(AuthenticatedUser.require(), executionId);
    }

    @PostMapping(path = "/{executionId}/nodes/{nodeId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Approve or reject a waiting Human Approval step and resume the run")
    public ExecutionDetailResponse decide(
            @PathVariable UUID executionId,
            @PathVariable String nodeId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return executionService.decide(AuthenticatedUser.requireRole(Role.MEMBER), executionId, nodeId, request);
    }

    @PostMapping("/{executionId}/retry")
    @Operation(summary = "Retry a failed run from its failed step")
    public ExecutionDetailResponse retry(
            @PathVariable UUID executionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return executionService.retry(AuthenticatedUser.requireRole(Role.MEMBER), executionId, idempotencyKey);
    }

    @PostMapping("/{executionId}/cancel")
    @Operation(summary = "Cancel a queued or running execution")
    public ExecutionDetailResponse cancel(@PathVariable UUID executionId) {
        return executionService.cancel(AuthenticatedUser.requireRole(Role.MEMBER), executionId);
    }

    @PostMapping("/{executionId}/replay")
    @Operation(summary = "Safely replay an execution with side-effect analysis")
    public com.flowops.api.ReplayResponse replay(
            @PathVariable UUID executionId,
            @RequestBody(required = false) com.flowops.api.ReplayRequest request) {
        return executionService.replay(AuthenticatedUser.requireRole(Role.MEMBER), executionId, request);
    }
}
