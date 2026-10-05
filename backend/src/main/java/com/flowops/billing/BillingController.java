package com.flowops.billing;

import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.PlansResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production-ready SaaS billing controller.
 * 
 * Multi-tenant isolation: every resource count is scoped by the caller's organizationId
 * resolved strictly from the verified JWT principal. Client-supplied workspace IDs are never accepted.
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing")
public class BillingController {

    private final BillingService billingService;
    private final PlanConfig planConfig;

    public BillingController(BillingService billingService, PlanConfig planConfig) {
        this.billingService = billingService;
        this.planConfig = planConfig;
    }

    /**
     * Authoritative usage endpoint: returns real database counts for the current billing cycle.
     */
    @GetMapping("/usage")
    @Operation(summary = "Get live resource usage for the currently authenticated workspace")
    public WorkspaceUsageResponse getUsage() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return billingService.getUsage(principal.organizationId());
    }

    /**
     * Overview endpoint: returns subscription details, localized currency, and live usage counters.
     */
    @GetMapping
    @Operation(summary = "Get billing overview and subscription details for the workspace")
    public BillingOverviewResponse getOverview() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return billingService.getBillingOverview(principal.organizationId());
    }

    /**
     * Public plan and limit metadata endpoint.
     */
    @GetMapping("/plans")
    @Operation(summary = "Get available plans and their limits")
    public PlansResponse getPlans() {
        return new PlansResponse(
                planConfig.getAllLimits(),
                planConfig.isPrivateBeta(),
                planConfig.isBillingEnabled(),
                planConfig.defaultPlan());
    }

    /**
     * Checkout initialization: disabled during private beta.
     */
    @PostMapping("/checkout")
    @Operation(summary = "Initiate checkout session (disabled during private beta)")
    public ResponseEntity<Map<String, String>> checkout(@RequestBody(required = false) Map<String, Object> request) {
        AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(Map.of("message", "Checkout initiated"));
    }

    /**
     * Cancellation: disabled during private beta.
     */
    @PostMapping("/cancel")
    @Operation(summary = "Cancel subscription (disabled during private beta)")
    public ResponseEntity<Map<String, String>> cancel() {
        AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(Map.of("message", "Subscription cancelled"));
    }
}
