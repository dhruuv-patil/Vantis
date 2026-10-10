package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.ExecutionEvent;
import com.flowops.domain.Workflow;
import com.flowops.repository.ExecutionEventRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic execution event ingestion for external workflow systems.
 *
 * <p>Allows external systems (n8n, Zapier, Temporal, custom orchestrators) to send
 * execution telemetry into FlowOps for anomaly detection, health scoring, and monitoring.
 *
 * <p>Events are scoped to the authenticated organization.
 */
@Service
public class ExecutionEventService {

    private static final Logger log =
            LoggerFactory.getLogger(ExecutionEventService.class);

    private final WorkflowRepository workflows;
    private final ExecutionEventRepository events;
    private final IntegrationRepository integrations;
    private final ObjectMapper mapper;

    public ExecutionEventService(
            WorkflowRepository workflows,
            ExecutionEventRepository events,
            IntegrationRepository integrations,
            ObjectMapper mapper) {
        this.workflows = workflows;
        this.events = events;
        this.integrations = integrations;
        this.mapper = mapper;
    }

    /**
     * Ingests a single execution event from an external system.
     */
    @Transactional
    public ExecutionEvent ingestEventInternal(
            FlowOpsPrincipal principal,
            ExecutionEventEnvelopes.IngestExecutionEventRequest request) {

        if (request.source() == null || request.source().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "source is required");
        }

        if (request.workflowExternalId() == null
                || request.workflowExternalId().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "workflowExternalId is required");
        }

        if (request.executionExternalId() == null
                || request.executionExternalId().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "executionExternalId is required");
        }

        if (request.stepExternalId() == null
                || request.stepExternalId().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "stepExternalId is required");
        }

        if (request.status() == null || request.status().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "status is required");
        }

        if (request.timestamp() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "timestamp is required");
        }

        UUID integrationId = request.integrationId();
        boolean providerRow = integrationId != null;

        // The idempotency identity below is integration-scoped, so it is only
        // authoritative if the integration belongs to the caller's org. Otherwise
        // a user could probe or read another tenant's events by quoting its
        // integration id. findByIdAndOrganizationId resolves both at once: a
        // missing row and a cross-tenant integration look identical (contract §2).
        if (providerRow) {
            integrations
                    .findByIdAndOrganizationId(integrationId, principal.organizationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_NOT_FOUND));
        }

        /*
         * Provider events are idempotent using:
         *
         * integration_id
         * workflow_external_id
         * execution_external_id
         * step_external_id
         */
        if (providerRow) {
            Optional<ExecutionEvent> existing =
                    events.findByIntegrationIdAndWorkflowExternalIdAndExecutionExternalIdAndStepExternalId(
                            integrationId,
                            request.workflowExternalId(),
                            request.executionExternalId(),
                            request.stepExternalId());

            if (existing.isPresent()) {
                log.debug(
                        "Skipping duplicate external event {}/{} for workflow {}",
                        request.workflowExternalId(),
                        request.executionExternalId(),
                        request.stepExternalId());

                return null;
            }
        }

        /*
         * Provider integrations pass the IntegrationWorkflow ID through
         * externalWorkflowRef.
         *
         * We intentionally do NOT create a native FlowOps Workflow for
         * provider-synced executions.
         */
        UUID workflowId;

        if (request.externalWorkflowRef() != null) {
            workflowId = request.externalWorkflowRef();
        } else {
            workflowId = findOrCreateExternalWorkflow(
                    principal,
                    request);
        }

        ExecutionEvent event = ExecutionEvent.create(
                principal.organizationId(),
                workflowId,
                request.source(),
                request.workflowExternalId(),
                request.executionExternalId(),
                request.stepExternalId(),
                request.stepName(),
                request.status(),
                request.startedAt(),
                request.finishedAt(),
                request.durationMs(),
                request.retryCount(),
                request.inputSize(),
                request.outputSize(),
                request.itemCount(),
                request.outputSignature(),
                request.httpStatus(),
                request.dataCompleteness(),
                request.runIndex(),
                request.errorType(),
                request.errorMessage(),
                request.metadata(),
                integrationId);

        events.save(event);

        log.info(
                "Ingested execution event from {} for workflow {} execution {} step {}",
                request.source(),
                workflowId,
                request.executionExternalId(),
                request.stepExternalId());

        return event;
    }

    /**
     * Public single-event ingest.
     */
    @Transactional
    public ExecutionEventEnvelopes.ExecutionEventResponse ingestEvent(
            FlowOpsPrincipal principal,
            ExecutionEventEnvelopes.IngestExecutionEventRequest request) {

        ExecutionEvent saved = ingestEventInternal(principal, request);

        if (saved != null) {
            return ExecutionEventEnvelopes.ExecutionEventResponse.of(saved);
        }

        return events
                .findByIntegrationIdAndWorkflowExternalIdAndExecutionExternalIdAndStepExternalId(
                        request.integrationId(),
                        request.workflowExternalId(),
                        request.executionExternalId(),
                        request.stepExternalId())
                .map(ExecutionEventEnvelopes.ExecutionEventResponse::of)
                .orElseThrow(() ->
                        new ApiException(
                                ErrorCode.VALIDATION_ERROR,
                                "event already exists"));
    }

    /**
     * Ingest multiple execution events in a batch.
     */
    @Transactional
    public ExecutionEventEnvelopes.BatchIngestResponse ingestBatch(
            FlowOpsPrincipal principal,
            ExecutionEventEnvelopes.BatchIngestRequest request) {

        List<ExecutionEventEnvelopes.IngestExecutionEventRequest> eventRequests =
                request.events();

        if (eventRequests == null || eventRequests.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "events array cannot be empty");
        }

        int accepted = 0;
        int rejected = 0;
        List<String> errors = new java.util.ArrayList<>();

        for (ExecutionEventEnvelopes.IngestExecutionEventRequest eventReq
                : eventRequests) {
            try {
                if (ingestEventInternal(principal, eventReq) == null) {
                    rejected++;
                } else {
                    accepted++;
                }
            } catch (Exception e) {
                rejected++;
                errors.add(e.getMessage());
            }
        }

        return new ExecutionEventEnvelopes.BatchIngestResponse(
                accepted,
                rejected,
                errors);
    }

    /**
     * Lists external execution events for the current organization.
     *
     * <p>Provider-synced events are stored against the authenticated
     * organization, so this query is the authoritative source for the
     * external executions page.
     */
    @Transactional(readOnly = true)
    public List<ExecutionEvent> listEvents(
            FlowOpsPrincipal principal,
            UUID workflowId,
            int limit) {

        int safeLimit = Math.min(
                Math.max(limit, 1),
                100);

        UUID organizationId = principal.organizationId();

        log.info(
                "EXECUTION EVENTS QUERY: orgId={}, workflowId={}, limit={}",
                organizationId,
                workflowId,
                safeLimit);

        List<ExecutionEvent> result;

        if (workflowId != null) {
            // Org-scoped by construction: a workflow id from another tenant's
            // events yields an empty list, never a cross-tenant read.
            result = events.findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(
                    organizationId,
                    workflowId,
                    PageRequest.of(0, safeLimit));
        } else {
            result = events.findByOrganizationIdOrderByCreatedAtDesc(
                    organizationId,
                    PageRequest.of(0, safeLimit));
        }

        log.info(
                "EXECUTION EVENTS RESULT: {} events returned for orgId={}",
                result.size(),
                organizationId);

        return result;
    }

    /**
     * Finds all events belonging to one external execution.
     *
     * <p>The organization ID is always taken from the authenticated principal
     * so an external execution from another organization cannot be exposed.
     */
    @Transactional(readOnly = true)
    public List<ExecutionEvent> findExternalExecution(
            FlowOpsPrincipal principal,
            String executionExternalId,
            String workflowExternalId) {

        if (executionExternalId == null || executionExternalId.isBlank()) {
            return List.of();
        }

        UUID organizationId = principal.organizationId();

        if (workflowExternalId != null && !workflowExternalId.isBlank()) {
            return events
                    .findByOrganizationIdAndWorkflowExternalIdAndExecutionExternalIdOrderByCreatedAtAsc(
                            organizationId,
                            workflowExternalId,
                            executionExternalId);
        }

        return events
                .findByOrganizationIdAndExecutionExternalIdOrderByCreatedAtAsc(
                        organizationId,
                        executionExternalId);
    }

    /**
     * Gets a single execution event by ID.
     */
    @Transactional(readOnly = true)
    public ExecutionEvent getEvent(
            FlowOpsPrincipal principal,
            UUID id) {

        return events.findByIdAndOrganizationId(
                        id,
                        principal.organizationId())
                .orElseThrow(() ->
                        new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * Finds an existing workflow or creates a minimal external workflow record.
     *
     * <p>This is only used by the legacy/custom ingestion path.
     * Provider integrations use IntegrationWorkflow.id instead.
     */
    private UUID findOrCreateExternalWorkflow(
            FlowOpsPrincipal principal,
            ExecutionEventEnvelopes.IngestExecutionEventRequest request) {

        String workflowName =
                request.workflowName() != null
                        && !request.workflowName().isBlank()
                ? request.workflowName()
                : request.source()
                        + " workflow: "
                        + request.workflowExternalId();

        Optional<Workflow> existing =
                workflows.findByNameAndOrganizationId(
                        workflowName,
                        principal.organizationId());

        if (existing.isPresent()) {
            return existing.get().getId();
        }

        Workflow workflow = Workflow.create(
                principal.organizationId(),
                principal.userId(),
                workflowName,
                "External workflow from "
                        + request.source()
                        + " (ID: "
                        + request.workflowExternalId()
                        + ")",
                null);

        workflows.save(workflow);

        return workflow.getId();
    }
}