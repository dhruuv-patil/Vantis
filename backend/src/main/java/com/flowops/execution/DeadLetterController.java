package com.flowops.execution;

import com.flowops.api.ReplayRequest;
import com.flowops.api.ReplayResponse;
import com.flowops.domain.DeadLetterRecord;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dead-letters")
@Tag(name = "Dead Letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;
    private final ExecutionService executionService;

    public DeadLetterController(
            DeadLetterService deadLetterService,
            ExecutionService executionService) {
        this.deadLetterService = deadLetterService;
        this.executionService = executionService;
    }

    @GetMapping
    @Operation(summary = "List dead letter records for the tenant")
    public List<DeadLetterRecord> list(
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return deadLetterService.list(principal.organizationId(), workflowId, status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get detailed dead letter record with input and context")
    public DeadLetterRecord get(@PathVariable UUID id) {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return deadLetterService.get(principal.organizationId(), id);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Mark a dead letter record as resolved")
    public void resolve(@PathVariable UUID id) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        deadLetterService.markResolved(principal.organizationId(), id);
    }

    @PostMapping("/{id}/replay")
    @Operation(summary = "Replay the unrecoverable execution captured in the dead letter store")
    public ReplayResponse replay(
            @PathVariable UUID id,
            @RequestBody(required = false) ReplayRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        DeadLetterRecord record = deadLetterService.get(principal.organizationId(), id);
        deadLetterService.markInvestigating(principal.organizationId(), id);

        ReplayResponse response = executionService.replay(principal, record.getExecutionId(), request);
        if (response.newExecutionId() != null) {
            deadLetterService.markReplayed(principal.organizationId(), id, response.newExecutionId());
        }
        return response;
    }
}
