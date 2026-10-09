package com.flowops.reliability;

import com.flowops.api.ReliabilityEnvelopes;
import com.flowops.domain.AnomalyStatus;
import com.flowops.security.AuthenticatedUser;
import com.flowops.domain.Role;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the reliability/observability layer (reliability pivot, M4).
 * All endpoints are org-scoped via {@link AuthenticatedUser}.
 */
@RestController
@RequestMapping("/api/reliability")
public class ReliabilityController {

    private final ReliabilityService service;
    private final RateLimiter rateLimiter;
    private final com.flowops.billing.service.EntitlementService entitlementService;

    @org.springframework.beans.factory.annotation.Autowired
    public ReliabilityController(
            ReliabilityService service,
            RateLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.billing.service.EntitlementService entitlementService) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.entitlementService = entitlementService;
    }

    public ReliabilityController(ReliabilityService service, RateLimiter rateLimiter) {
        this(service, rateLimiter, null);
    }

    // =========================================================================
    // Anomalies
    // =========================================================================

    /**
     * GET /api/reliability/anomalies
     *
     * Query params:
     * - status: OPEN | ACKNOWLEDGED | RESOLVED | FALSE_POSITIVE
     * - workflowId: UUID
     */
    @GetMapping("/anomalies")
    public ReliabilityEnvelopes.AnomaliesResponse listAnomalies(
            AuthenticatedUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workflowId) {

        Optional<AnomalyStatus> parsedStatus = parseStatus(status);

        return service.listAnomalies(
                user,
                parsedStatus,
                Optional.ofNullable(workflowId));
    }

    /** GET /api/reliability/anomalies/{id} */
    @GetMapping("/anomalies/{id}")
    public ReliabilityEnvelopes.AnomalyDetailResponse getAnomaly(
            AuthenticatedUser user,
            @PathVariable UUID id) {

        return service.getAnomaly(user, id);
    }

    /** POST /api/reliability/anomalies/{id}/acknowledge */
    @PostMapping("/anomalies/{id}/acknowledge")
    public ReliabilityEnvelopes.AcknowledgeResponse acknowledge(
            AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody(required = false)
                    ReliabilityEnvelopes.AcknowledgeRequest request) {

        AuthenticatedUser.requireRole(Role.MEMBER);

        String note = request != null ? request.note() : null;

        return service.acknowledge(user, id, note);
    }

    /** POST /api/reliability/anomalies/{id}/resolve */
    @PostMapping("/anomalies/{id}/resolve")
    public ReliabilityEnvelopes.ResolveResponse resolve(
            AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody(required = false)
                    ReliabilityEnvelopes.ResolveRequest request) {

        AuthenticatedUser.requireRole(Role.MEMBER);

        String note = request != null ? request.note() : null;

        return service.resolve(user, id, note);
    }

    /** POST /api/reliability/anomalies/{id}/false-positive */
    @PostMapping("/anomalies/{id}/false-positive")
    public ReliabilityEnvelopes.FalsePositiveResponse markFalsePositive(
            AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody(required = false)
                    ReliabilityEnvelopes.FalsePositiveRequest request) {

        AuthenticatedUser.requireRole(Role.MEMBER);

        String reason = (request != null
                && request.reason() != null
                && !request.reason().isBlank())
                ? request.reason()
                : "Marked by user";

        return service.markFalsePositive(user, id, reason);
    }

    /** POST /api/reliability/anomalies/{id}/verify-recovery */
    @PostMapping("/anomalies/{id}/verify-recovery")
    public ReliabilityEnvelopes.VerifyRecoveryResponse verifyRecovery(
            AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody(required = false)
                    ReliabilityEnvelopes.VerifyRecoveryRequest request) {

        AuthenticatedUser.requireRole(Role.MEMBER);

        Integer count = request != null ? request.requiredCount() : null;

        return service.startVerification(user, id, count);
    }

    /** GET /api/reliability/anomalies/{id}/recovery */
    @GetMapping("/anomalies/{id}/recovery")
    public ReliabilityEnvelopes.RecoveryStatusResponse getRecoveryStatus(
            AuthenticatedUser user,
            @PathVariable UUID id) {

        return service.getRecoveryStatus(user, id);
    }

    // =========================================================================
    // Workflow health
    // =========================================================================

    /** GET /api/reliability/workflows/{id}/health */
    @GetMapping("/workflows/{id}/health")
    public ReliabilityEnvelopes.WorkflowHealthResponse getWorkflowHealth(
            AuthenticatedUser user,
            @PathVariable UUID id) {

        return service.getWorkflowHealth(user, id);
    }

    // =========================================================================
    // Workflow metrics
    // =========================================================================

    /** GET /api/reliability/workflows/{id}/metrics */
    @GetMapping("/workflows/{id}/metrics")
    public ReliabilityEnvelopes.WorkflowMetricsResponse getWorkflowMetrics(
            AuthenticatedUser user,
            @PathVariable UUID id) {

        return service.getWorkflowMetrics(user, id);
    }

    // =========================================================================
    // AI investigation (on-demand only)
    // =========================================================================

    /** POST /api/reliability/anomalies/{id}/investigate */
    @PostMapping("/anomalies/{id}/investigate")
    public ReliabilityEnvelopes.InvestigateResponse investigateAnomaly(
            AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody(required = false)
                    ReliabilityEnvelopes.InvestigateRequest request) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.MEMBER);
        rateLimiter.check("ai:investigate:" + principal.userId(), 20, Duration.ofMinutes(1));
        rateLimiter.check("ai:investigate:org:" + principal.organizationId(), 20, Duration.ofHours(1));

        com.flowops.billing.service.EntitlementService.InvestigationReservationTicket ticket =
                com.flowops.billing.service.EntitlementService.InvestigationReservationTicket.notReserved();

        if (entitlementService != null) {
            ticket = entitlementService.reserveAiInvestigation(principal.organizationId());
        }

        ReliabilityEnvelopes.InvestigateResponse response;
        try {
            response = service.investigateAnomaly(user, id);
        } catch (RuntimeException e) {
            if (entitlementService != null) {
                entitlementService.refundAiInvestigation(principal.organizationId(), ticket);
            }
            throw e;
        }

        // If the AI provider returned configured=false, refund quota
        if (response != null && response.result() != null && !response.result().configured()) {
            if (entitlementService != null) {
                entitlementService.refundAiInvestigation(principal.organizationId(), ticket);
            }
        }

        return response;
    }

    private Optional<AnomalyStatus> parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    AnomalyStatus.valueOf(status.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            // Let the existing API error handling layer deal with invalid input.
            // Returning empty here avoids coupling this controller to a
            // non-existing ServiceException type.
            return Optional.empty();
        }
    }
}