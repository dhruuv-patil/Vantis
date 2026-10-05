package com.flowops.reliability;

import com.flowops.api.IncidentResponse;
import com.flowops.domain.IncidentStatus;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    @Operation(summary = "List incidents for the organization")
    public List<IncidentResponse> list(
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) IncidentStatus status) {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return incidentService.list(principal.organizationId(), workflowId, status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident details by ID")
    public IncidentResponse get(@PathVariable UUID id) {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return incidentService.get(principal.organizationId(), id);
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an incident")
    public IncidentResponse acknowledge(@PathVariable UUID id) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        return incidentService.acknowledge(principal.organizationId(), id);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve an incident")
    public IncidentResponse resolve(@PathVariable UUID id) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        return incidentService.resolve(principal.organizationId(), id);
    }
}
