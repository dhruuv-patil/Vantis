package com.flowops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.BillingPeriod;
import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.service.BillingNoticeService;
import com.flowops.billing.service.BillingPeriodService;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Integration;
import com.flowops.domain.Subscription;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.InvitationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.WorkflowRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrationEntitlementTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private SubscriptionRepository subscriptionRepository;
    private IntegrationRepository integrationRepository;
    private BillingPeriodService billingPeriodService;
    private UsageMeterService usageMeterService;
    private WorkflowRepository workflowRepository;
    private InvitationRepository invitationRepository;
    private OrganizationMemberRepository memberRepository;
    private BillingNoticeService billingNoticeService;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        integrationRepository = mock(IntegrationRepository.class);
        billingPeriodService = mock(BillingPeriodService.class);
        usageMeterService = mock(UsageMeterService.class);
        workflowRepository = mock(WorkflowRepository.class);
        invitationRepository = mock(InvitationRepository.class);
        memberRepository = mock(OrganizationMemberRepository.class);
        billingNoticeService = mock(BillingNoticeService.class);
    }

    private EntitlementService createEntitlementService(String enforcementMode) {
        return new EntitlementService(
                subscriptionRepository,
                usageMeterService,
                billingPeriodService,
                workflowRepository,
                invitationRepository,
                memberRepository,
                billingNoticeService,
                null,
                MAPPER,
                new SimpleMeterRegistry(),
                integrationRepository,
                enforcementMode);
    }

    private void mockSubscription(String planId) {
        Subscription sub = Subscription.create(
                ORG_ID,
                planId,
                "ACTIVE",
                "USD",
                "US",
                "MONTHLY",
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(86400 * 30));
        when(subscriptionRepository.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(sub));
        when(billingPeriodService.resolvePeriod(sub)).thenReturn(
                new BillingPeriod(sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd()));
    }

    @Test
    @DisplayName("Free plan allows 1 integration instance and blocks 2nd in block mode")
    void freePlanAllowsOneAndBlocksSecond() {
        EntitlementService entitlementService = createEntitlementService("enforce");
        mockSubscription("FREE");

        // 0 connected integrations -> allowed
        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(0L);
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isTrue();
        entitlementService.assertCanConnectIntegration(ORG_ID); // does not throw

        // 1 connected integration -> limit reached (Free limit = 1)
        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(1L);
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isFalse();

        assertThatThrownBy(() -> entitlementService.assertCanConnectIntegration(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(apiEx.details().limitKey()).isEqualTo("integrations");
                    assertThat(apiEx.details().current()).isEqualTo(1L);
                    assertThat(apiEx.details().max()).isEqualTo(1L);
                    assertThat(apiEx.details().planId()).isEqualTo("FREE");
                    assertThat(apiEx.details().suggestedPlan()).isEqualTo("PRO");
                });
    }

    @Test
    @DisplayName("Pro plan allows up to 5 integration instances and blocks 6th")
    void proPlanAllowsUpToFiveIntegrations() {
        EntitlementService entitlementService = createEntitlementService("enforce");
        mockSubscription("PRO");

        // 4 connected -> allowed
        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(4L);
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isTrue();
        entitlementService.assertCanConnectIntegration(ORG_ID);

        // 5 connected -> limit reached (Pro limit = 5)
        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(5L);
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isFalse();

        assertThatThrownBy(() -> entitlementService.assertCanConnectIntegration(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(apiEx.details().limitKey()).isEqualTo("integrations");
                    assertThat(apiEx.details().current()).isEqualTo(5L);
                    assertThat(apiEx.details().max()).isEqualTo(5L);
                    assertThat(apiEx.details().planId()).isEqualTo("PRO");
                });
    }

    @Test
    @DisplayName("Log mode does not throw ApiException when integration limit is exceeded")
    void logModeDoesNotThrowWhenLimitExceeded() {
        EntitlementService entitlementService = createEntitlementService("log");
        mockSubscription("FREE");

        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(2L);

        // canConnectIntegration still accurately reports boolean
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isFalse();

        // assertCanConnectIntegration logs and does not throw in log mode
        entitlementService.assertCanConnectIntegration(ORG_ID);
    }

    @Test
    @DisplayName("Off mode bypasses entitlement checks completely")
    void offModeBypassesChecks() {
        EntitlementService entitlementService = createEntitlementService("off");
        mockSubscription("FREE");

        when(integrationRepository.countByOrganizationIdAndStatus(ORG_ID, Integration.STATUS_CONNECTED))
                .thenReturn(10L);

        // In OFF mode, assert does not throw
        entitlementService.assertCanConnectIntegration(ORG_ID);
    }

    @Test
    @DisplayName("Fails open when subscription lookup or database fails")
    void failsOpenOnSubscriptionError() {
        EntitlementService entitlementService = createEntitlementService("enforce");

        // Missing subscription -> fail open
        when(subscriptionRepository.findByWorkspaceId(ORG_ID)).thenReturn(Optional.empty());
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isTrue();
        entitlementService.assertCanConnectIntegration(ORG_ID);

        // DB error -> fail open
        when(subscriptionRepository.findByWorkspaceId(ORG_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB connection down"));
        assertThat(entitlementService.canConnectIntegration(ORG_ID)).isTrue();
        entitlementService.assertCanConnectIntegration(ORG_ID);
    }
}
