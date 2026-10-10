package com.flowops.billing;

import com.flowops.billing.dto.AdminBillingOverrideRequest;
import com.flowops.billing.dto.AdminBillingOverrideResponse;
import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.BillingStatusResponse;
import com.flowops.billing.dto.CancelSubscriptionRequestDto;
import com.flowops.billing.dto.CancelSubscriptionResponse;
import com.flowops.billing.dto.ChangePlanRequestDto;
import com.flowops.billing.dto.ChangePlanResponse;
import com.flowops.billing.dto.CheckoutRequest;
import com.flowops.billing.dto.CheckoutResponse;
import com.flowops.billing.dto.EntitlementsResponse;
import com.flowops.billing.dto.FoundingOfferResponse;
import com.flowops.billing.dto.InvoiceResponse;
import com.flowops.billing.dto.PlansResponse;
import com.flowops.billing.dto.PortalRequest;
import com.flowops.billing.dto.PortalResponse;
import com.flowops.billing.dto.ResumeSubscriptionResponse;
import com.flowops.billing.dto.SubscriptionSummaryResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.domain.Role;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production-ready SaaS billing lifecycle controller.
 * 
 * Multi-tenant isolation: every endpoint operates strictly on the organizationId
 * resolved from the authenticated JWT principal (AuthenticatedUser.require()).
 * Client-supplied organization IDs are never accepted.
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing")
public class BillingController {

    public static final int CHECKOUT_RATE_LIMIT = 5;
    public static final Duration CHECKOUT_RATE_WINDOW = Duration.ofMinutes(1);

    private final BillingService billingService;
    private final PlanConfig planConfig;
    private final RateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    public BillingController(
            BillingService billingService,
            PlanConfig planConfig,
            RateLimiter rateLimiter) {
        this.billingService = billingService;
        this.planConfig = planConfig;
        this.rateLimiter = rateLimiter;
    }

    public BillingController(BillingService billingService, PlanConfig planConfig) {
        this(billingService, planConfig, null);
    }

    /**
     * Authoritative usage endpoint: returns real database counts for current billing cycle.
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
     * 6.1 Subscription summary: returns complete billing and subscription state.
     */
    @GetMapping("/subscription")
    @Operation(summary = "Get subscription summary and quota limits for the workspace")
    public ResponseEntity<SubscriptionSummaryResponse> getSubscription() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return ResponseEntity.ok(billingService.getSubscriptionSummary(principal.organizationId()));
    }

    /**
     * 6.9 Entitlements endpoint: live quota usage against PlanCatalog.
     */
    @GetMapping("/entitlements")
    @Operation(summary = "Get detailed entitlement quotas and live usage for the workspace")
    public ResponseEntity<EntitlementsResponse> getEntitlements() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return ResponseEntity.ok(billingService.getEntitlements(principal.organizationId()));
    }

    /**
     * 6.10 Billing status endpoint: aggregated bootstrap state for frontend.
     */
    @GetMapping("/status")
    @Operation(summary = "Get aggregated billing bootstrap status")
    public ResponseEntity<BillingStatusResponse> getStatus() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return ResponseEntity.ok(billingService.getBillingStatus(principal.organizationId()));
    }

    /**
     * 6.2 Checkout initialization: creates checkout session with provider.
     * Protected by organization/user scoped rate limiting.
     */
    @PostMapping("/checkout")
    @Operation(summary = "Initiate checkout session with active billing provider")
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody(required = false) CheckoutRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        if (rateLimiter == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Checkout rate limiter is unavailable");
        }
        rateLimiter.check(
                "billing:checkout:org:" + principal.organizationId() + ":user:" + principal.userId(),
                CHECKOUT_RATE_LIMIT,
                CHECKOUT_RATE_WINDOW
        );
        return ResponseEntity.ok(billingService.createCheckoutSession(principal.organizationId(), principal.userId(), principal.email(), request));
    }

    /**
     * 6.3 Customer portal: creates management session with provider.
     */
    @PostMapping("/portal")
    @Operation(summary = "Create customer portal session for self-service subscription management")
    public ResponseEntity<PortalResponse> portal(@RequestBody(required = false) PortalRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(billingService.createPortalSession(principal.organizationId(), principal.userId(), principal.email(), request));
    }

    /**
     * 6.4 Change plan: modifies subscription plan via provider.
     */
    @PostMapping("/change-plan")
    @Operation(summary = "Upgrade or downgrade workspace subscription plan")
    public ResponseEntity<ChangePlanResponse> changePlan(@RequestBody(required = false) ChangePlanRequestDto request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(billingService.changePlan(principal.organizationId(), principal.userId(), request));
    }

    /**
     * 6.5 Cancel subscription: cancels subscription immediately or at period end.
     */
    @PostMapping("/cancel")
    @Operation(summary = "Cancel subscription immediately or at period end")
    public ResponseEntity<CancelSubscriptionResponse> cancel(@RequestBody(required = false) CancelSubscriptionRequestDto request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(billingService.cancelSubscription(principal.organizationId(), principal.userId(), request));
    }

    public ResponseEntity<CancelSubscriptionResponse> cancel() {
        return cancel(null);
    }

    /**
     * 6.6 Resume subscription: resumes subscription scheduled for cancellation at period end.
     */
    @PostMapping("/resume")
    @Operation(summary = "Resume subscription scheduled for period-end cancellation")
    public ResponseEntity<ResumeSubscriptionResponse> resume() {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        if (!planConfig.isBillingEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Billing will be available after the private beta.");
        }
        return ResponseEntity.ok(billingService.resumeSubscription(principal.organizationId(), principal.userId()));
    }

    /**
     * 6.7 Invoices: lists recent customer invoices from provider.
     */
    @GetMapping("/invoices")
    @Operation(summary = "List recent invoices from billing provider")
    public ResponseEntity<List<InvoiceResponse>> getInvoices() {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        return ResponseEntity.ok(billingService.listInvoices(principal.organizationId()));
    }

    /**
     * 6.8 Founding offer: applies 30% discount for 12 months for eligible organization.
     */
    @PostMapping("/founding-offer")
    @Operation(summary = "Apply founding member offer (30% off for 12 months)")
    public ResponseEntity<FoundingOfferResponse> applyFoundingOffer() {
        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);
        return ResponseEntity.ok(billingService.applyFoundingOffer(principal.organizationId()));
    }

    /**
     * 6.14 Admin Override: strongly protected platform-admin endpoint to override subscription plan/status.
     * Accessible only by PLATFORM_ADMIN, fully audited, target-organization scoped.
     */
    @PostMapping("/admin/override")
    @Operation(summary = "Platform administrator override for organization billing state")
    public ResponseEntity<AdminBillingOverrideResponse> adminOverride(@RequestBody AdminBillingOverrideRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.requirePlatformAdmin();
        return ResponseEntity.ok(billingService.adminOverrideSubscription(principal.userId(), request));
    }
}
