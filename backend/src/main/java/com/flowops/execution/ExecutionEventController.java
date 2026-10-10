package com.flowops.execution;

import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.domain.ExecutionEvent;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/execution-events")
@Tag(name = "Execution Events (External)")
public class ExecutionEventController {

    private final ExecutionEventService eventService;
    private final RateLimiter rateLimiter;

    @Autowired
    public ExecutionEventController(
            ExecutionEventService eventService,
            @Autowired(required = false) RateLimiter rateLimiter) {
        this.eventService = eventService;
        this.rateLimiter = rateLimiter;
    }

    public ExecutionEventController(ExecutionEventService eventService) {
        this(eventService, null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Ingest a single execution event from an external workflow system")
    public ExecutionEventEnvelopes.ExecutionEventResponse ingestEvent(
            @Valid @RequestBody
                    ExecutionEventEnvelopes.IngestExecutionEventRequest request) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        if (rateLimiter != null) {
            rateLimiter.check("execution-events:ingest:" + principal.organizationId(), 300, Duration.ofMinutes(1));
        }

        return eventService.ingestEvent(principal, request);
    }

    @PostMapping(
            path = "/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Ingest multiple execution events in a batch")
    public ExecutionEventEnvelopes.BatchIngestResponse ingestBatch(
            @Valid @RequestBody
                    ExecutionEventEnvelopes.BatchIngestRequest request) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        if (rateLimiter != null) {
            rateLimiter.check("execution-events:ingest:" + principal.organizationId(), 300, Duration.ofMinutes(1));
        }

        return eventService.ingestBatch(principal, request);
    }

    @GetMapping
    @Operation(
            summary = "List execution events for the current organization")
    public ExecutionEventEnvelopes.ExecutionEventsResponse listEvents(
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) String executionExternalId,
            @RequestParam(required = false) String workflowExternalId,
            @RequestParam(required = false, defaultValue = "50") int limit) {

        FlowOpsPrincipal principal = AuthenticatedUser.require();
        if (rateLimiter != null) {
            rateLimiter.check("execution-events:query:" + principal.organizationId(), 120, Duration.ofMinutes(1));
        }

        List<ExecutionEvent> events;
        if (executionExternalId != null && !executionExternalId.isBlank()) {
            events = eventService.findExternalExecution(principal, executionExternalId, workflowExternalId);
        } else {
            events = eventService.listEvents(principal, workflowId, limit);
        }

        var responseEvents = events.stream()
                .map(ExecutionEventEnvelopes.ExecutionEvent::of)
                .toList();

        return new ExecutionEventEnvelopes.ExecutionEventsResponse(responseEvents);
    }

    @GetMapping("/external/{executionExternalId}")
    @Operation(
            summary = "Get all events for an external execution ID")
    public ExecutionEventEnvelopes.ExecutionEventsResponse getExternalExecution(
            @PathVariable String executionExternalId,
            @RequestParam(required = false) String workflowExternalId) {

        FlowOpsPrincipal principal = AuthenticatedUser.require();
        if (rateLimiter != null) {
            rateLimiter.check("execution-events:query:" + principal.organizationId(), 120, Duration.ofMinutes(1));
        }

        var events = eventService.findExternalExecution(
                principal,
                executionExternalId,
                workflowExternalId);

        var responseEvents = events.stream()
                .map(ExecutionEventEnvelopes.ExecutionEvent::of)
                .toList();

        return new ExecutionEventEnvelopes.ExecutionEventsResponse(responseEvents);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a single execution event by ID")
    public ExecutionEventEnvelopes.ExecutionEventDetailResponse getEvent(
            @PathVariable UUID id) {

        FlowOpsPrincipal principal = AuthenticatedUser.require();

        var event = eventService.getEvent(
                principal,
                id);

        return new ExecutionEventEnvelopes.ExecutionEventDetailResponse(
                ExecutionEventEnvelopes.ExecutionEvent.of(event));
    }
}