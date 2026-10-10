package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.flowops.billing.dto.PortalRequest;
import com.flowops.billing.dto.PortalResponse;
import com.flowops.billing.dto.ResumeSubscriptionResponse;
import com.flowops.billing.dto.SubscriptionSummaryResponse;
import com.flowops.billing.provider.BillingProvider;
import com.flowops.billing.provider.exception.BillingConfigurationException;
import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CreatePortalSessionRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.InvoiceResult;
import com.flowops.billing.provider.model.PortalSessionResult;
import com.flowops.billing.provider.model.ResumeSubscriptionRequest;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.stripe.StripeProperties;
import com.flowops.billing.service.BillingPeriodService;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.SubscriptionLifecycleService;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.domain.BillingAuditLog;
import com.flowops.domain.BillingDiscount;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.repository.AiUsageEventRepository;
import com.flowops.repository.BillingAuditLogRepository;
import com.flowops.repository.BillingDiscountRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class BillingApiControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

    private static final FlowOpsPrincipal ADMIN_PRINCIPAL_A =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_A, Role.ADMIN, "admin-a@example.com");

    private static final FlowOpsPrincipal OWNER_PRINCIPAL_A =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_A, Role.OWNER, "owner-a@example.com");

    private static final FlowOpsPrincipal PLATFORM_ADMIN_PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_A, Role.PLATFORM_ADMIN, "platform-admin@vantis.run");

    private static final FlowOpsPrincipal MEMBER_PRINCIPAL_A =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_A, Role.MEMBER, "member-a@example.com");

    private static final FlowOpsPrincipal ADMIN_PRINCIPAL_B =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_B, Role.ADMIN, "admin-b@example.com");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private AiUsageEventRepository aiUsageEventRepository;
    @Mock private BillingProvider billingProvider;
    @Mock private SubscriptionLifecycleService subscriptionLifecycleService;
    @Mock private BillingDiscountRepository discountRepository;
    @Mock private UsageMeterService usageMeterService;
    @Mock private BillingAuditLogRepository auditLogRepository;
    @Mock private EntitlementService entitlementService;

    private RateLimiter rateLimiter;
    private StripeProperties stripeProperties;
    private BillingPeriodService billingPeriodService;
    private PlanConfig planConfigEnabled;
    private PlanConfig planConfigDisabled;
    private BillingService billingServiceEnabled;
    private BillingService billingServiceDisabled;
    private BillingController controllerEnabled;
    private BillingController controllerDisabled;

    @BeforeEach
    void setUp() {
        billingPeriodService = new BillingPeriodService();
        planConfigEnabled = new PlanConfig(true, false);
        planConfigDisabled = new PlanConfig(false, true);

        rateLimiter = new RateLimiter(true);
        stripeProperties = new StripeProperties();
        stripeProperties.setSecretKey("sk_test_123");
        stripeProperties.setWebhookSecret("whsec_123");
        Map<String, String> prices = new HashMap<>();
        prices.put("pro.usd.monthly", "price_pro_usd_monthly");
        prices.put("pro.usd.yearly", "price_pro_usd_yearly");
        prices.put("pro.eur.monthly", "price_pro_eur_monthly");
        prices.put("pro.gbp.monthly", "price_pro_gbp_monthly");
        prices.put("business.usd.monthly", "price_biz_usd_monthly");
        prices.put("business.usd.yearly", "price_biz_usd_yearly");
        stripeProperties.setPrices(prices);

        billingServiceEnabled = new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                planConfigEnabled,
                entitlementService,
                billingProvider,
                subscriptionLifecycleService,
                discountRepository,
                usageMeterService,
                billingPeriodService,
                auditLogRepository,
                stripeProperties,
                null);

        billingServiceDisabled = new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                planConfigDisabled,
                entitlementService,
                billingProvider,
                subscriptionLifecycleService,
                discountRepository,
                usageMeterService,
                billingPeriodService,
                auditLogRepository,
                stripeProperties,
                null);

        controllerEnabled = new BillingController(billingServiceEnabled, planConfigEnabled, rateLimiter);
        controllerDisabled = new BillingController(billingServiceDisabled, planConfigDisabled, rateLimiter);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(FlowOpsPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private Subscription createSubscription(UUID orgId, String plan, String status) {
        BillingPeriod period = BillingPeriod.currentMonth();
        Subscription sub = Subscription.create(
                orgId, plan, status, "USD", "US", "MONTHLY", period.start(), period.end());
        sub.setProviderCustomerId("cus_" + orgId);
        sub.setProviderSubscriptionId("sub_" + orgId);
        return sub;
    }

    // =========================================================================
    // 1. GET SUBSCRIPTION SUMMARY
    // =========================================================================

    @Test
    @DisplayName("1. GET /api/billing/subscription returns complete subscription summary with quotas and usage")
    void test1_getSubscriptionSummary() {
        authenticate(MEMBER_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        sub.setCancelAtPeriodEnd(false);
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));

        when(workflowRepository.countActiveByOrganizationId(ORG_A)).thenReturn(5L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("executions"))).thenReturn(1200L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_generations"))).thenReturn(25L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_investigations"))).thenReturn(2L);
        when(usageMeterService.getLifetimeTrialUsage(ORG_A)).thenReturn(0L);
        when(memberRepository.countByOrganizationId(ORG_A)).thenReturn(3L);

        ResponseEntity<SubscriptionSummaryResponse> response = controllerEnabled.getSubscription();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        SubscriptionSummaryResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.organizationId()).isEqualTo(ORG_A);
        assertThat(body.plan()).isEqualTo("PRO");
        assertThat(body.billingStatus()).isEqualTo("ACTIVE");
        assertThat(body.renewalActive()).isTrue();
        assertThat(body.cancelAtPeriodEnd()).isFalse();

        // Check usage summary
        assertThat(body.usage().workflows()).isEqualTo(5L);
        assertThat(body.usage().executions()).isEqualTo(1200L);
        assertThat(body.usage().aiGenerations()).isEqualTo(25L);
        assertThat(body.usage().seats()).isEqualTo(3L);

        // Check remaining usage
        assertThat(body.remainingUsage().workflows()).isEqualTo(95L); // 100 - 5
        assertThat(body.remainingUsage().executions()).isEqualTo(48800L); // 50,000 - 1200
        assertThat(body.remainingUsage().aiGenerations()).isEqualTo(475L); // 500 - 25

        // Transitions available
        assertThat(body.availablePlanTransitions()).containsExactly("FREE", "BUSINESS");
    }

    // =========================================================================
    // 2. CHECKOUT SESSION
    // =========================================================================

    @Test
    @DisplayName("2. POST /api/billing/checkout initiates provider session without prematurely updating local plan")
    void test2_checkoutSessionCreated() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any(CreateCheckoutSessionRequest.class)))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", "https://app.vantis.run/success", "https://app.vantis.run/cancel");
        ResponseEntity<CheckoutResponse> response = controllerEnabled.checkout(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        CheckoutResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.sessionId()).isEqualTo("cs_123");
        assertThat(body.checkoutUrl()).isEqualTo("https://checkout.stripe.com/cs_123");
        assertThat(body.provider()).isEqualTo("stripe");

        // Verify provider call arguments
        ArgumentCaptor<CreateCheckoutSessionRequest> captor = ArgumentCaptor.forClass(CreateCheckoutSessionRequest.class);
        verify(billingProvider).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_A);
        assertThat(captor.getValue().planId()).isEqualTo("PRO");

        // Proves local plan remains unchanged before webhook delivery
        assertThat(sub.getPlan()).isEqualTo("FREE");
    }

    // =========================================================================
    // 3. CUSTOMER PORTAL
    // =========================================================================

    @Test
    @DisplayName("3. POST /api/billing/portal creates self-service portal session with provider")
    void test3_createPortalSession() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.createPortalSession(any(CreatePortalSessionRequest.class)))
                .thenReturn(new PortalSessionResult("https://billing.stripe.com/session_abc"));

        PortalRequest request = new PortalRequest("https://app.vantis.run/billing");
        ResponseEntity<PortalResponse> response = controllerEnabled.portal(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().portalUrl()).isEqualTo("https://billing.stripe.com/session_abc");

        ArgumentCaptor<CreatePortalSessionRequest> captor = ArgumentCaptor.forClass(CreatePortalSessionRequest.class);
        verify(billingProvider).createPortalSession(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_A);
        assertThat(captor.getValue().customerId()).isEqualTo("cus_" + ORG_A);
    }

    // =========================================================================
    // 4. CHANGE PLAN
    // =========================================================================

    @Test
    @DisplayName("4. POST /api/billing/change-plan invokes provider to change plan")
    void test4_changePlan() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        Instant now = Instant.now();
        when(billingProvider.changePlan(any(ChangePlanRequest.class)))
                .thenReturn(new SubscriptionResult("sub_" + ORG_A, "cus_" + ORG_A, "BUSINESS", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), false));

        ChangePlanRequestDto request = new ChangePlanRequestDto("BUSINESS", "monthly");
        ResponseEntity<ChangePlanResponse> response = controllerEnabled.changePlan(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ChangePlanResponse body = response.getBody();
        assertThat(body.plan()).isEqualTo("BUSINESS");
        assertThat(body.success()).isTrue();

        ArgumentCaptor<ChangePlanRequest> captor = ArgumentCaptor.forClass(ChangePlanRequest.class);
        verify(billingProvider).changePlan(captor.capture());
        assertThat(captor.getValue().planId()).isEqualTo("BUSINESS");
    }

    // =========================================================================
    // 5. CANCEL SUBSCRIPTION
    // =========================================================================

    @Test
    @DisplayName("5. POST /api/billing/cancel cancels subscription at period end without mutating authoritative local state before webhook")
    void test5_cancelSubscriptionAtPeriodEnd() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        Instant now = Instant.now();
        when(billingProvider.cancelSubscription(any(CancelSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResult("sub_" + ORG_A, "cus_" + ORG_A, "PRO", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), true));

        CancelSubscriptionRequestDto request = new CancelSubscriptionRequestDto(true);
        ResponseEntity<CancelSubscriptionResponse> response = controllerEnabled.cancel(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().cancelAtPeriodEnd()).isTrue();

        // Webhooks remain authoritative: local DB subscription is NOT mutated before webhook delivery
        verify(subscriptionRepository, never()).save(sub);
        assertThat(sub.isCancelAtPeriodEnd()).isFalse();
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // 6. RESUME SUBSCRIPTION
    // =========================================================================

    @Test
    @DisplayName("6. POST /api/billing/resume resumes cancellation-pending subscription without mutating authoritative local state before webhook")
    void test6_resumeSubscription() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        sub.setCancelAtPeriodEnd(true);
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        Instant now = Instant.now();
        when(billingProvider.resumeSubscription(any(ResumeSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResult("sub_" + ORG_A, "cus_" + ORG_A, "PRO", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), false));

        ResponseEntity<ResumeSubscriptionResponse> response = controllerEnabled.resume();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().cancelAtPeriodEnd()).isFalse();
        // Webhooks remain authoritative: local DB subscription is NOT mutated before webhook delivery
        verify(subscriptionRepository, never()).save(sub);
        assertThat(sub.isCancelAtPeriodEnd()).isTrue();
        verify(billingProvider).resumeSubscription(any(ResumeSubscriptionRequest.class));
    }

    // =========================================================================
    // 7. LIST INVOICES
    // =========================================================================

    @Test
    @DisplayName("7. GET /api/billing/invoices returns sanitized invoice list from provider")
    void test7_listInvoices() {
        authenticate(MEMBER_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        Instant paid = Instant.now().minus(5, ChronoUnit.DAYS);
        when(billingProvider.listInvoices(eq("cus_" + ORG_A), anyInt()))
                .thenReturn(List.of(
                        new InvoiceResult("in_1", "cus_" + ORG_A, 2900L, "USD", "paid", paid, "https://hosted/in_1", "https://pdf/in_1"),
                        new InvoiceResult("in_2", "cus_" + ORG_A, 2900L, "USD", "paid", paid.minus(30, ChronoUnit.DAYS), "https://hosted/in_2", "https://pdf/in_2")
                ));

        ResponseEntity<List<InvoiceResponse>> response = controllerEnabled.getInvoices();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<InvoiceResponse> body = response.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).id()).isEqualTo("in_1");
        assertThat(body.get(0).amountPaid()).isEqualTo(2900L);
        assertThat(body.get(0).currency()).isEqualTo("USD");
        assertThat(body.get(0).hostedInvoiceUrl()).isEqualTo("https://hosted/in_1");
    }

    // =========================================================================
    // 8. FOUNDING OFFER APPLICATION
    // =========================================================================

    @Test
    @DisplayName("8. POST /api/billing/founding-offer delegates to SubscriptionLifecycleService")
    void test8_applyFoundingOffer() {
        authenticate(ADMIN_PRINCIPAL_A);
        Instant now = Instant.now();
        BillingDiscount discount = BillingDiscount.create(
                ORG_A, "FOUNDING_30", 30, now, now.plus(365, ChronoUnit.DAYS), "FOUNDING_30");
        when(subscriptionLifecycleService.applyFoundingOffer(ORG_A)).thenReturn(discount);

        ResponseEntity<FoundingOfferResponse> response = controllerEnabled.applyFoundingOffer();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        FoundingOfferResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("FOUNDING_30");
        assertThat(body.discountPercentage()).isEqualTo(30);
        assertThat(body.active()).isTrue();
    }

    // =========================================================================
    // 9. USAGE / ENTITLEMENTS ENDPOINT
    // =========================================================================

    @Test
    @DisplayName("9. GET /api/billing/entitlements returns quotas and live percentages")
    void test9_getEntitlements() {
        authenticate(MEMBER_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));

        when(workflowRepository.countActiveByOrganizationId(ORG_A)).thenReturn(50L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("executions"))).thenReturn(25000L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_generations"))).thenReturn(250L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_investigations"))).thenReturn(25L);
        when(usageMeterService.getLifetimeTrialUsage(ORG_A)).thenReturn(0L);
        when(memberRepository.countByOrganizationId(ORG_A)).thenReturn(5L);

        ResponseEntity<EntitlementsResponse> response = controllerEnabled.getEntitlements();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        EntitlementsResponse body = response.getBody();
        assertThat(body.plan()).isEqualTo("PRO");
        assertThat(body.workflows().used()).isEqualTo(50L);
        assertThat(body.workflows().limit()).isEqualTo(100L);
        assertThat(body.workflows().percentage()).isEqualTo(50);

        assertThat(body.executions().used()).isEqualTo(25000L);
        assertThat(body.executions().limit()).isEqualTo(50000L);
        assertThat(body.executions().percentage()).isEqualTo(50);

        assertThat(body.allowedChannels()).contains("EMAIL", "SLACK", "PAGERDUTY");
        assertThat(body.anomalyMode()).isEqualTo("full");
    }

    // =========================================================================
    // 10. PROVIDER DISABLED (BILLING_ENABLED=false)
    // =========================================================================

    @Test
    @DisplayName("10. Mutating endpoints throw BILLING_DISABLED when billing is disabled")
    void test10_providerDisabled() {
        authenticate(ADMIN_PRINCIPAL_A);

        assertThatThrownBy(() -> controllerDisabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));

        assertThatThrownBy(() -> controllerDisabled.portal(new PortalRequest(null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));

        assertThatThrownBy(() -> controllerDisabled.changePlan(new ChangePlanRequestDto("PRO", "monthly")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));

        assertThatThrownBy(() -> controllerDisabled.cancel(new CancelSubscriptionRequestDto(true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));

        assertThatThrownBy(() -> controllerDisabled.resume())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));
    }

    // =========================================================================
    // 11. PROVIDER FAILURE
    // =========================================================================

    @Test
    @DisplayName("11. Provider API exceptions are wrapped in structured ApiException(INTERNAL_ERROR)")
    void test11_providerFailure() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.createCheckoutSession(any()))
                .thenThrow(new RuntimeException("Stripe API connection timed out"));

        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        assertThatThrownBy(() -> controllerEnabled.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(apiEx.getMessage()).contains("Stripe API connection timed out");
                });
    }

    // =========================================================================
    // 12. INVALID PLAN
    // =========================================================================

    @Test
    @DisplayName("12. Checkout with invalid plan throws VALIDATION_ERROR")
    void test12_invalidPlan() {
        authenticate(ADMIN_PRINCIPAL_A);

        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("ULTRA_ENTERPRISE", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // =========================================================================
    // 13. INVALID TRANSITIONS
    // =========================================================================

    @Test
    @DisplayName("13. Invalid transitions throw VALIDATION_ERROR")
    void test13_invalidTransitions() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));

        // Attempting to checkout when already on PRO
        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("already subscribed to PRO");
                });

        // Attempting to change plan to same plan
        assertThatThrownBy(() -> controllerEnabled.changePlan(new ChangePlanRequestDto("PRO", "monthly")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("already on PRO");
                });

        // Attempting to resume when not scheduled for cancellation
        sub.setCancelAtPeriodEnd(false);
        assertThatThrownBy(() -> controllerEnabled.resume())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("not scheduled for cancellation");
                });
    }

    // =========================================================================
    // 14. UNAUTHORIZED ORGANIZATION (ROLE ENFORCEMENT)
    // =========================================================================

    @Test
    @DisplayName("14. Non-admin members receive FORBIDDEN_ROLE on mutating operations")
    void test14_unauthorizedRole() {
        authenticate(MEMBER_PRINCIPAL_A);

        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        assertThatThrownBy(() -> controllerEnabled.portal(new PortalRequest(null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        assertThatThrownBy(() -> controllerEnabled.changePlan(new ChangePlanRequestDto("BUSINESS", "monthly")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        assertThatThrownBy(() -> controllerEnabled.cancel(new CancelSubscriptionRequestDto(true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        assertThatThrownBy(() -> controllerEnabled.resume())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        assertThatThrownBy(() -> controllerEnabled.applyFoundingOffer())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
    }

    // =========================================================================
    // 15. CROSS-TENANT ACCESS PREVENTION
    // =========================================================================

    @Test
    @DisplayName("15. Multi-tenant isolation: Tenant B never accesses or mutates Tenant A")
    void test15_crossTenantIsolation() {
        // Authenticate as Tenant B Admin
        authenticate(ADMIN_PRINCIPAL_B);

        Subscription subB = createSubscription(ORG_B, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_B)).thenReturn(Optional.of(subB));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_b", "https://checkout.stripe.com/cs_b", "USD", "open", null));

        // When Tenant B calls checkout, orgId is strictly resolved as ORG_B
        controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null));

        ArgumentCaptor<CreateCheckoutSessionRequest> captor = ArgumentCaptor.forClass(CreateCheckoutSessionRequest.class);
        verify(billingProvider).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_B);
        assertThat(captor.getValue().organizationId()).isNotEqualTo(ORG_A);

        // Verify Org A was never queried or touched
        verify(subscriptionRepository, never()).findByWorkspaceId(ORG_A);
    }

    // =========================================================================
    // 16. IDEMPOTENT FOUNDING OFFER
    // =========================================================================

    @Test
    @DisplayName("16. Repeated application of founding offer throws VALIDATION_ERROR")
    void test16_idempotentFoundingOffer() {
        authenticate(ADMIN_PRINCIPAL_A);

        when(subscriptionLifecycleService.applyFoundingOffer(ORG_A))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_ERROR, "Founding offer has already been applied for this organization."));

        assertThatThrownBy(() -> controllerEnabled.applyFoundingOffer())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("Founding offer has already been applied");
                });
    }

    // =========================================================================
    // AGGREGATED BILLING STATUS
    // =========================================================================

    @Test
    @DisplayName("GET /api/billing/status aggregates subscription, entitlements, and lifecycle state")
    void testBillingStatusAggregation() {
        authenticate(MEMBER_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        sub.setPastDueSince(Instant.now().minus(2, ChronoUnit.DAYS));
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");

        when(workflowRepository.countActiveByOrganizationId(ORG_A)).thenReturn(10L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("executions"))).thenReturn(500L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_generations"))).thenReturn(5L);
        when(usageMeterService.getCurrentUsage(eq(ORG_A), any(), eq("ai_investigations"))).thenReturn(0L);
        when(usageMeterService.getLifetimeTrialUsage(ORG_A)).thenReturn(0L);
        when(memberRepository.countByOrganizationId(ORG_A)).thenReturn(2L);

        ResponseEntity<BillingStatusResponse> response = controllerEnabled.getStatus();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        BillingStatusResponse body = response.getBody();
        assertThat(body.provider()).isEqualTo("stripe");
        assertThat(body.isBillingEnabled()).isTrue();
        assertThat(body.dunningActive()).isTrue();
        assertThat(body.pastDueSince()).isNotNull();
        assertThat(body.subscription().plan()).isEqualTo("PRO");
        assertThat(body.entitlements().plan()).isEqualTo("PRO");
    }

    // =========================================================================
    // 17. CHECKOUT RATE LIMITING
    // =========================================================================

    @Test
    @DisplayName("17.1 Checkout rate limit: requests within limit succeed; excessive attempts throw RATE_LIMITED")
    void test17_checkoutRateLimitEnforced() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        // First 5 requests within window succeed
        for (int i = 0; i < BillingController.CHECKOUT_RATE_LIMIT; i++) {
            ResponseEntity<CheckoutResponse> res = controllerEnabled.checkout(request);
            assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        }

        // 6th request within window is rejected with 429 RATE_LIMITED
        assertThatThrownBy(() -> controllerEnabled.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(apiEx.retryAfterSeconds()).isNotNull();
                    assertThat(apiEx.retryAfterSeconds()).isGreaterThan(0L);
                });
    }

    @Test
    @DisplayName("17.2 Checkout rate limit: organization and user scoped; Tenant B is not affected by Tenant A exhaustion")
    void test17_checkoutRateLimitOrgIsolated() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription subA = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(subA));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        // Exhaust Org A
        for (int i = 0; i < BillingController.CHECKOUT_RATE_LIMIT; i++) {
            controllerEnabled.checkout(request);
        }
        assertThatThrownBy(() -> controllerEnabled.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.RATE_LIMITED));

        // Now authenticate as Org B: Org B has its own rate limit quota and succeeds
        authenticate(ADMIN_PRINCIPAL_B);
        Subscription subB = createSubscription(ORG_B, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_B)).thenReturn(Optional.of(subB));

        ResponseEntity<CheckoutResponse> resB = controllerEnabled.checkout(request);
        assertThat(resB.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("17.3 Disabled billing returns BILLING_DISABLED before rate limit check")
    void test17_billingDisabledPrecedesRateLimit() {
        authenticate(ADMIN_PRINCIPAL_A);
        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> controllerDisabled.checkout(request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));
        }
    }

    @Test
    @DisplayName("17.4 Checkout rate limiter cannot fail open: absence/unavailability of limiter throws INTERNAL_ERROR when billing enabled")
    void test17_missingRateLimiterFailsClosedWhenBillingEnabled() {
        authenticate(ADMIN_PRINCIPAL_A);
        BillingController controllerWithoutLimiter = new BillingController(billingServiceEnabled, planConfigEnabled, (RateLimiter) null);
        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        assertThatThrownBy(() -> controllerWithoutLimiter.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(apiEx.getMessage()).contains("rate limiter is unavailable");
                });

        // Also verify that BILLING_DISABLED takes precedence even when limiter is absent
        BillingController controllerDisabledWithoutLimiter = new BillingController(billingServiceDisabled, planConfigDisabled, (RateLimiter) null);
        assertThatThrownBy(() -> controllerDisabledWithoutLimiter.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BILLING_DISABLED));
    }

    // =========================================================================
    // 18. STRICT CURRENCY + INTERVAL VALIDATION
    // =========================================================================

    @Test
    @DisplayName("18.1 Supported currencies succeed; unknown/unsupported currencies throw VALIDATION_ERROR")
    void test18_currencyValidation() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        // Valid currencies
        assertThat(controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "EUR", null, null)).getStatusCode().is2xxSuccessful()).isTrue();

        // Unsupported currency: XYZ
        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "XYZ", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("Unsupported currency");
                });
    }

    @Test
    @DisplayName("18.2 Omitted currency falls back to subscription currency or USD")
    void test18_currencyOmittedUsdFallback() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        sub.setCurrency("EUR");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "EUR", "open", null));

        // When currency is null, resolves to subscription's EUR
        ResponseEntity<CheckoutResponse> res = controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", null, null, null));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        ArgumentCaptor<CreateCheckoutSessionRequest> captor = ArgumentCaptor.forClass(CreateCheckoutSessionRequest.class);
        verify(billingProvider).createCheckoutSession(captor.capture());
        assertThat(captor.getValue().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("18.3 Supported intervals succeed; invalid intervals throw VALIDATION_ERROR")
    void test18_intervalValidation() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        // Valid intervals: monthly and yearly
        assertThat(controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controllerEnabled.checkout(new CheckoutRequest("PRO", "yearly", "USD", null, null)).getStatusCode().is2xxSuccessful()).isTrue();

        // Invalid intervals: weekly, quarterly, etc.
        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "weekly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "quarterly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // =========================================================================
    // 19. PRICE INTEGRITY
    // =========================================================================

    @Test
    @DisplayName("19. Price integrity: missing configured price ID for plan + currency + interval throws VALIDATION_ERROR")
    void test19_unconfiguredPriceCombinationRejected() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");

        // GBP + yearly is not configured in stripeProperties mock prices
        CheckoutRequest request = new CheckoutRequest("PRO", "yearly", "GBP", null, null);

        assertThatThrownBy(() -> controllerEnabled.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getMessage()).contains("No configured price found");
                });
    }

    // =========================================================================
    // 20. BILLING AUDIT LOGGING
    // =========================================================================

    @Test
    @DisplayName("20.1 Billing audit logs: all 6 required actions distinguish requested/initiated from authoritative state")
    void test20_allRequiredAuditedActionsRecorded() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        sub.setProviderCustomerId("cus_123");
        sub.setProviderSubscriptionId("sub_123");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        Instant now = Instant.now();

        // 1. CHECKOUT_INITIATED
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));
        controllerEnabled.checkout(new CheckoutRequest("BUSINESS", "monthly", "USD", null, null));

        // 2. PORTAL_SESSION_CREATED
        when(billingProvider.createPortalSession(any()))
                .thenReturn(new PortalSessionResult("https://billing.stripe.com/p/session_123"));
        controllerEnabled.portal(null);

        // 3. PLAN_CHANGE_REQUESTED
        when(billingProvider.changePlan(any()))
                .thenReturn(new SubscriptionResult("sub_123", "cus_123", "BUSINESS", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), false));
        controllerEnabled.changePlan(new ChangePlanRequestDto("BUSINESS", "monthly"));

        // 4. CANCELLATION_REQUESTED
        when(billingProvider.cancelSubscription(any()))
                .thenReturn(new SubscriptionResult("sub_123", "cus_123", "BUSINESS", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), true));
        controllerEnabled.cancel(new CancelSubscriptionRequestDto(true));

        // 5. RESUME_REQUESTED
        sub.setCancelAtPeriodEnd(true);
        when(billingProvider.resumeSubscription(any()))
                .thenReturn(new SubscriptionResult("sub_123", "cus_123", "BUSINESS", "active", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), false));
        controllerEnabled.resume();

        // 6. ADMIN_OVERRIDE
        authenticate(PLATFORM_ADMIN_PRINCIPAL);
        controllerEnabled.adminOverride(new AdminBillingOverrideRequest(ORG_A, "PRO", "ACTIVE", false, "Platform admin override test"));

        ArgumentCaptor<BillingAuditLog> logCaptor = ArgumentCaptor.forClass(BillingAuditLog.class);
        verify(auditLogRepository, times(6)).save(logCaptor.capture());
        List<BillingAuditLog> capturedLogs = logCaptor.getAllValues();

        List<String> actions = capturedLogs.stream().map(BillingAuditLog::getAction).toList();
        assertThat(actions).containsExactly(
                "CHECKOUT_INITIATED",
                "PORTAL_SESSION_CREATED",
                "PLAN_CHANGE_REQUESTED",
                "CANCELLATION_REQUESTED",
                "RESUME_REQUESTED",
                "ADMIN_OVERRIDE"
        );

        // Verify all 6 audit logs have strictly sanitized metadata with NO credentials, secrets, or card numbers
        for (BillingAuditLog log : capturedLogs) {
            assertThat(log.getOrganizationId()).isEqualTo(ORG_A);
            if (log.getMetadata() != null) {
                String metaStr = log.getMetadata().toString();
                assertThat(metaStr).doesNotContain("secret");
                assertThat(metaStr).doesNotContain("sk_live");
                assertThat(metaStr).doesNotContain("card_number");
                assertThat(metaStr).doesNotContain("cvv");
                assertThat(metaStr).doesNotContain("webhook_signature");
            }
        }
    }

    @Test
    @DisplayName("20.2 Audit persistence failure throws INTERNAL_ERROR and does not silently disappear")
    void test20_auditPersistenceFailureThrowsInternalError() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        // Simulate database persistence failure on audit log
        doThrow(new RuntimeException("PostgreSQL connection lost during audit write"))
                .when(auditLogRepository).save(any(BillingAuditLog.class));

        assertThatThrownBy(() -> controllerEnabled.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(apiEx.getMessage()).contains("Failed to persist billing audit log");
                });
    }

    @Test
    @DisplayName("20.3 Missing audit log repository throws INTERNAL_ERROR and never proceeds without audit")
    void test20_missingAuditLogRepositoryThrows() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSessionResult("cs_123", "https://checkout.stripe.com/cs_123", "USD", "open", null));

        BillingService serviceWithoutAuditRepo = new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                planConfigEnabled,
                entitlementService,
                billingProvider,
                subscriptionLifecycleService,
                discountRepository,
                usageMeterService,
                billingPeriodService,
                (BillingAuditLogRepository) null,
                stripeProperties,
                null);

        BillingController controllerWithoutAuditRepo = new BillingController(serviceWithoutAuditRepo, planConfigEnabled, rateLimiter);

        assertThatThrownBy(() -> controllerWithoutAuditRepo.checkout(new CheckoutRequest("PRO", "monthly", "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(apiEx.getMessage()).contains("Billing audit logging repository is not available");
                });
    }

    // =========================================================================
    // 21. PLATFORM ADMIN OVERRIDE ENDPOINT
    // =========================================================================

    @Test
    @DisplayName("21.1 Admin override: unauthenticated caller rejected with 401")
    void test21_unauthenticatedRejected() {
        SecurityContextHolder.clearContext();
        AdminBillingOverrideRequest request = new AdminBillingOverrideRequest(ORG_A, "PRO", "ACTIVE", false, "Support escalation");

        assertThatThrownBy(() -> controllerEnabled.adminOverride(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    @DisplayName("21.2 Admin override: tenant ADMIN and OWNER rejected with 403 FORBIDDEN_ROLE")
    void test21_tenantAdminAndOwnerForbidden() {
        AdminBillingOverrideRequest request = new AdminBillingOverrideRequest(ORG_A, "PRO", "ACTIVE", false, "Support escalation");

        // Tenant ADMIN forbidden
        authenticate(ADMIN_PRINCIPAL_A);
        assertThatThrownBy(() -> controllerEnabled.adminOverride(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

        // Tenant OWNER forbidden
        authenticate(OWNER_PRINCIPAL_A);
        assertThatThrownBy(() -> controllerEnabled.adminOverride(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
    }

    @Test
    @DisplayName("21.3 Admin override: PLATFORM_ADMIN succeeds, mutates subscription in DB, evicts cache, and records audit log")
    void test21_platformAdminSucceeds() {
        authenticate(PLATFORM_ADMIN_PRINCIPAL);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));

        AdminBillingOverrideRequest request = new AdminBillingOverrideRequest(ORG_A, "BUSINESS", "ACTIVE", false, "Enterprise VIP customer upgrade");
        ResponseEntity<AdminBillingOverrideResponse> response = controllerEnabled.adminOverride(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        AdminBillingOverrideResponse body = response.getBody();
        assertThat(body.organizationId()).isEqualTo(ORG_A);
        assertThat(body.previousPlan()).isEqualTo("FREE");
        assertThat(body.newPlan()).isEqualTo("BUSINESS");
        assertThat(body.reason()).isEqualTo("Enterprise VIP customer upgrade");

        // Verify DB update
        verify(subscriptionRepository).save(sub);
        assertThat(sub.getPlan()).isEqualTo("BUSINESS");

        // Verify cache eviction
        verify(entitlementService).evictPlanCache(ORG_A);

        // Verify audit log
        ArgumentCaptor<BillingAuditLog> logCaptor = ArgumentCaptor.forClass(BillingAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        BillingAuditLog audit = logCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("ADMIN_OVERRIDE");
        assertThat(audit.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(audit.getFromPlan()).isEqualTo("FREE");
        assertThat(audit.getToPlan()).isEqualTo("BUSINESS");
        assertThat(audit.getReason()).isEqualTo("Enterprise VIP customer upgrade");
    }

    @Test
    @DisplayName("21.4 Admin override: mandatory reason and valid plan validation")
    void test21_adminOverrideValidation() {
        authenticate(PLATFORM_ADMIN_PRINCIPAL);

        // Missing reason rejected
        AdminBillingOverrideRequest missingReason = new AdminBillingOverrideRequest(ORG_A, "PRO", "ACTIVE", false, "   ");
        assertThatThrownBy(() -> controllerEnabled.adminOverride(missingReason))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // Invalid plan rejected
        AdminBillingOverrideRequest invalidPlan = new AdminBillingOverrideRequest(ORG_A, "SUPER_SECRET_PLAN", "ACTIVE", false, "Valid reason");
        assertThatThrownBy(() -> controllerEnabled.adminOverride(invalidPlan))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("21.5 Admin override: audit persistence failure prevents completion and throws INTERNAL_ERROR")
    void test21_adminOverrideAuditPersistenceFailureThrows() {
        authenticate(PLATFORM_ADMIN_PRINCIPAL);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));

        doThrow(new RuntimeException("Audit persistence DB failure"))
                .when(auditLogRepository).save(any(BillingAuditLog.class));

        AdminBillingOverrideRequest request = new AdminBillingOverrideRequest(ORG_A, "BUSINESS", "ACTIVE", false, "Upgrade");

        assertThatThrownBy(() -> controllerEnabled.adminOverride(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(apiEx.getMessage()).contains("Failed to persist billing audit log");
                });
    }

    // =========================================================================
    // 22. WEBHOOKS REMAIN AUTHORITATIVE (CASES A, C, D)
    // =========================================================================

    @Test
    @DisplayName("22.1 Case A: Cancel API succeeds but local authoritative DB subscription is NOT marked CANCELED before webhook")
    void test22_cancelApiDoesNotMutateDbBeforeWebhook() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        Instant now = Instant.now();
        when(billingProvider.cancelSubscription(any(CancelSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResult("sub_" + ORG_A, "cus_" + ORG_A, "PRO", "canceled", "monthly", "USD", now, now.plus(30, ChronoUnit.DAYS), false));

        // Immediate cancel requested
        CancelSubscriptionRequestDto request = new CancelSubscriptionRequestDto(false);
        ResponseEntity<CancelSubscriptionResponse> response = controllerEnabled.cancel(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Crucial invariant: subscriptionRepository.save(sub) was NEVER called!
        verify(subscriptionRepository, never()).save(sub);
        // Subscription status in DB remains ACTIVE!
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("22.2 Case C: Provider cancel operation fails; local subscription remains unchanged")
    void test22_cancelProviderFailureLeavesSubscriptionUnchanged() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.cancelSubscription(any(CancelSubscriptionRequest.class)))
                .thenThrow(new RuntimeException("Stripe API network timeout"));

        CancelSubscriptionRequestDto request = new CancelSubscriptionRequestDto(true);

        assertThatThrownBy(() -> controllerEnabled.cancel(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        verify(subscriptionRepository, never()).save(sub);
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("22.3 Case D: Resume API succeeds but local DB subscription does NOT become ACTIVE before webhook")
    void test22_resumeApiDoesNotMutateDbBeforeWebhook() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "PRO", "PAST_DUE");
        sub.setCancelAtPeriodEnd(true);
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.resumeSubscription(any(ResumeSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResult("sub_" + ORG_A, "cus_" + ORG_A, "PRO", "active", "monthly", "USD", Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS), false));

        ResponseEntity<ResumeSubscriptionResponse> response = controllerEnabled.resume();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        // Crucial invariant: local DB record was NOT mutated!
        verify(subscriptionRepository, never()).save(sub);
        assertThat(sub.getStatus()).isEqualTo("PAST_DUE");
        assertThat(sub.isCancelAtPeriodEnd()).isTrue();
    }

    // =========================================================================
    // 23. BILLING PROVIDER FAILURE SAFETY
    // =========================================================================

    @Test
    @DisplayName("23. Checkout provider failure leaves subscription record completely unchanged")
    void test23_checkoutProviderFailureSafe() {
        authenticate(ADMIN_PRINCIPAL_A);
        Subscription sub = createSubscription(ORG_A, "FREE", "ACTIVE");
        when(subscriptionRepository.findByWorkspaceId(ORG_A)).thenReturn(Optional.of(sub));
        when(billingProvider.getName()).thenReturn("stripe");
        when(billingProvider.createCheckoutSession(any()))
                .thenThrow(new RuntimeException("Provider connection lost"));

        CheckoutRequest request = new CheckoutRequest("PRO", "monthly", "USD", null, null);

        assertThatThrownBy(() -> controllerEnabled.checkout(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        // Plan remains FREE, never falsely activated
        assertThat(sub.getPlan()).isEqualTo("FREE");
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // 24. BILLING ENABLED / PROVIDER CONFIGURATION FAIL-FAST
    // =========================================================================

    @Test
    @DisplayName("24.1 BILLING_ENABLED=true fails fast at startup if BillingProvider is missing/null")
    void test24_billingEnabledWithNullProviderFailsFast() {
        PlanConfig enabledConfig = new PlanConfig(true, false);

        assertThatThrownBy(() -> new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                enabledConfig,
                null,
                null, // null provider when billing is enabled
                null,
                null,
                null,
                null))
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Billing is enabled (BILLING_ENABLED=true) but no BillingProvider bean was configured");
    }

    @Test
    @DisplayName("24.2 BILLING_ENABLED=false safely falls back to NoopBillingProvider")
    void test24_billingDisabledAllowsNoopProvider() {
        PlanConfig disabledConfig = new PlanConfig(false, true);

        BillingService service = new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                disabledConfig,
                null,
                null, // null provider when billing is disabled
                null,
                null,
                null,
                null);

        assertThat(service).isNotNull();
    }
}
