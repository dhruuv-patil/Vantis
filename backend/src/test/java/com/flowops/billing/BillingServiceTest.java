package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.billing.dto.BillingOverviewResponse;
import com.flowops.billing.dto.WorkspaceUsageResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AiUsageEvent;
import com.flowops.domain.Subscription;
import com.flowops.repository.AiUsageEventRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final UUID ORG_1 = UUID.randomUUID();
    private static final UUID ORG_2 = UUID.randomUUID();

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private OrganizationMemberRepository memberRepository;

    @Mock
    private AiUsageEventRepository aiUsageEventRepository;

    private PlanConfig planConfig;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        planConfig = new PlanConfig();
        billingService = new BillingService(
                subscriptionRepository,
                executionRepository,
                workflowRepository,
                memberRepository,
                aiUsageEventRepository,
                planConfig);
    }

    @Test
    @DisplayName("getOrCreateSubscription initializes a default subscription for a new workspace")
    void initializesDefaultSubscriptionForNewWorkspace() {
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Subscription sub = billingService.getOrCreateSubscription(ORG_1);

        assertThat(sub).isNotNull();
        assertThat(sub.getWorkspaceId()).isEqualTo(ORG_1);
        assertThat(sub.getPlan()).isEqualTo("PRIVATE_BETA");
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
        assertThat(sub.getCurrency()).isEqualTo("USD");
        assertThat(sub.getBillingCountry()).isEqualTo("US");
        verify(subscriptionRepository).save(any(Subscription.class));
    }

    @Test
    @DisplayName("getUsage returns real 0 counters for an empty/new workspace without mock defaults")
    void emptyWorkspaceReturnsRealZeroes() {
        BillingPeriod period = BillingPeriod.currentMonth();
        Subscription sub = Subscription.create(
                ORG_1, "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY",
                period.start(), period.end());
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.of(sub));

        when(executionRepository.countByOrganizationIdAndPeriod(eq(ORG_1), any(), any())).thenReturn(0L);
        when(aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(eq(ORG_1), any(), any())).thenReturn(0L);
        when(workflowRepository.countActiveByOrganizationId(ORG_1)).thenReturn(0L);
        when(memberRepository.countByOrganizationId(ORG_1)).thenReturn(1L);

        WorkspaceUsageResponse usage = billingService.getUsage(ORG_1);

        assertThat(usage.executions().used()).isEqualTo(0L);
        assertThat(usage.executions().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_EXECUTIONS_PER_MONTH);
        assertThat(usage.executions().percentage()).isEqualTo(0);

        assertThat(usage.aiGenerations().used()).isEqualTo(0L);
        assertThat(usage.aiGenerations().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_AI_GENERATIONS);
        assertThat(usage.aiGenerations().percentage()).isEqualTo(0);

        assertThat(usage.activeWorkflows().used()).isEqualTo(0L);
        assertThat(usage.activeWorkflows().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.BETA_ACTIVE_WORKFLOWS);
        assertThat(usage.activeWorkflows().percentage()).isEqualTo(0);

        assertThat(usage.teamMembers().used()).isEqualTo(1L);
        assertThat(usage.teamMembers().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_SEATS);
        assertThat(usage.teamMembers().percentage()).isEqualTo(10);
    }

    @Test
    @DisplayName("getUsage returns real calculated numbers for active workspace")
    void activeWorkspaceReturnsAccurateUsage() {
        BillingPeriod period = BillingPeriod.currentMonth();
        Subscription sub = Subscription.create(
                ORG_1, "PRIVATE_BETA", "ACTIVE", "INR", "IN", "MONTHLY",
                period.start(), period.end());
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.of(sub));

        when(executionRepository.countByOrganizationIdAndPeriod(eq(ORG_1), any(), any())).thenReturn(37L);
        when(aiUsageEventRepository.countUsageByOrganizationIdAndPeriod(eq(ORG_1), any(), any())).thenReturn(8L);
        when(workflowRepository.countActiveByOrganizationId(ORG_1)).thenReturn(4L);
        when(memberRepository.countByOrganizationId(ORG_1)).thenReturn(2L);

        WorkspaceUsageResponse usage = billingService.getUsage(ORG_1);

        assertThat(usage.executions().used()).isEqualTo(37L);
        assertThat(usage.executions().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_EXECUTIONS_PER_MONTH);

        assertThat(usage.aiGenerations().used()).isEqualTo(8L);
        assertThat(usage.aiGenerations().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_AI_GENERATIONS);

        assertThat(usage.activeWorkflows().used()).isEqualTo(4L);
        assertThat(usage.activeWorkflows().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.BETA_ACTIVE_WORKFLOWS);

        assertThat(usage.teamMembers().used()).isEqualTo(2L);
        assertThat(usage.teamMembers().limit()).isEqualTo((long) com.flowops.billing.model.PlanCatalog.PRO_SEATS);
    }

    @Test
    @DisplayName("multi-tenant isolation: Org 1 usage queries never leak Org 2 data")
    void multiTenantIsolation() {
        BillingPeriod period = BillingPeriod.currentMonth();
        Subscription sub1 = Subscription.create(
                ORG_1, "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY",
                period.start(), period.end());
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.of(sub1));

        billingService.getUsage(ORG_1);

        verify(executionRepository).countByOrganizationIdAndPeriod(eq(ORG_1), any(), any());
        verify(aiUsageEventRepository).countUsageByOrganizationIdAndPeriod(eq(ORG_1), any(), any());
        verify(workflowRepository).countActiveByOrganizationId(eq(ORG_1));
        verify(memberRepository).countByOrganizationId(eq(ORG_1));
    }

    @Test
    @DisplayName("getBillingOverview resolves localized symbols accurately")
    void overviewResolvesLocalizedSymbols() {
        BillingPeriod period = BillingPeriod.currentMonth();
        Subscription inrSub = Subscription.create(
                ORG_1, "PRIVATE_BETA", "ACTIVE", "INR", "IN", "MONTHLY",
                period.start(), period.end());
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.of(inrSub));
        when(memberRepository.countByOrganizationId(ORG_1)).thenReturn(1L);

        BillingOverviewResponse inrOverview = billingService.getBillingOverview(ORG_1);
        assertThat(inrOverview.currencySymbol()).isEqualTo("₹");
        assertThat(inrOverview.billingCurrency()).isEqualTo("INR");
        assertThat(inrOverview.isPrivateBeta()).isTrue();
        assertThat(inrOverview.isBillingEnabled()).isFalse();

        // USD
        Subscription usdSub = Subscription.create(
                ORG_2, "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY",
                period.start(), period.end());
        when(subscriptionRepository.findByWorkspaceId(ORG_2)).thenReturn(Optional.of(usdSub));
        when(memberRepository.countByOrganizationId(ORG_2)).thenReturn(1L);

        BillingOverviewResponse usdOverview = billingService.getBillingOverview(ORG_2);
        assertThat(usdOverview.currencySymbol()).isEqualTo("$");
        assertThat(usdOverview.billingCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("period rollover rolls over to current calendar month if stored period is in the past")
    void periodRolloverUpdatesSubscription() {
        Instant pastStart = Instant.now().minus(60, ChronoUnit.DAYS);
        Instant pastEnd = Instant.now().minus(30, ChronoUnit.DAYS);
        Subscription oldSub = Subscription.create(
                ORG_1, "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY",
                pastStart, pastEnd);
        when(subscriptionRepository.findByWorkspaceId(ORG_1)).thenReturn(Optional.of(oldSub));
        when(memberRepository.countByOrganizationId(ORG_1)).thenReturn(1L);

        WorkspaceUsageResponse usage = billingService.getUsage(ORG_1);

        BillingPeriod current = BillingPeriod.currentMonth();
        assertThat(usage.period().start()).isEqualTo(current.start());
        assertThat(usage.period().end()).isEqualTo(current.end());
        verify(subscriptionRepository).save(oldSub);
    }
}
