package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.PlansResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Role;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class BillingControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

    private static final FlowOpsPrincipal ADMIN_PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_ID, Role.ADMIN, "admin@example.com");

    private static final FlowOpsPrincipal MEMBER_PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_ID, Role.MEMBER, "member@example.com");

    @Mock
    private BillingService billingService;

    private PlanConfig planConfig;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        planConfig = new PlanConfig();
        controller = new BillingController(billingService, planConfig);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(FlowOpsPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    @DisplayName("GET /api/billing/usage uses authenticated principal org ID, never accepts client param")
    void getUsageScopedToAuthenticatedOrg() {
        authenticate(MEMBER_PRINCIPAL);

        WorkspaceUsageResponse expected = new WorkspaceUsageResponse(
                new WorkspaceUsageResponse.PeriodDto(Instant.now(), Instant.now()),
                "PRIVATE_BETA",
                new WorkspaceUsageResponse.MetricDto(0, 10000),
                new WorkspaceUsageResponse.MetricDto(0, 100),
                new WorkspaceUsageResponse.MetricDto(0, 50),
                new WorkspaceUsageResponse.MetricDto(1, 5));

        when(billingService.getUsage(ORG_ID)).thenReturn(expected);

        WorkspaceUsageResponse result = controller.getUsage();

        assertThat(result).isSameAs(expected);
        verify(billingService).getUsage(ORG_ID);
    }

    @Test
    @DisplayName("GET /api/billing returns workspace billing overview")
    void getOverviewReturnsAuthoritativeData() {
        authenticate(MEMBER_PRINCIPAL);

        BillingOverviewResponse expected = new BillingOverviewResponse(
                "PRIVATE_BETA",
                "ACTIVE",
                "US",
                "USD",
                "$",
                "None — Private Beta",
                true,
                false,
                "Private Beta — Pro features are currently free.",
                null);

        when(billingService.getBillingOverview(ORG_ID)).thenReturn(expected);

        BillingOverviewResponse result = controller.getOverview();

        assertThat(result).isSameAs(expected);
        verify(billingService).getBillingOverview(ORG_ID);
    }

    @Test
    @DisplayName("GET /api/billing/plans returns plan limits and private beta flags")
    void getPlansReturnsMetadata() {
        PlansResponse result = controller.getPlans();

        assertThat(result.plans()).containsKey("FREE");
        assertThat(result.plans()).containsKey("PRIVATE_BETA");
        assertThat(result.plans()).containsKey("PRO");
        assertThat(result.plans()).containsKey("BUSINESS");
        assertThat(result.isPrivateBeta()).isTrue();
        assertThat(result.isBillingEnabled()).isFalse();
    }

    @Test
    @DisplayName("POST /api/billing/checkout throws BILLING_DISABLED during private beta")
    void checkoutDisabledDuringPrivateBeta() {
        authenticate(ADMIN_PRINCIPAL);

        assertThatThrownBy(() -> controller.checkout(null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.BILLING_DISABLED);
                    assertThat(apiEx.getMessage()).contains("Billing will be available after the private beta.");
                });
    }

    @Test
    @DisplayName("POST /api/billing/cancel throws BILLING_DISABLED during private beta")
    void cancelDisabledDuringPrivateBeta() {
        authenticate(ADMIN_PRINCIPAL);

        assertThatThrownBy(() -> controller.cancel())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiEx = (ApiException) e;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.BILLING_DISABLED);
                });
    }
}
