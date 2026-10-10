package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.ai.AiController;
import com.flowops.ai.AiServiceClient;
import com.flowops.ai.GenerateWorkflowRequest;
import com.flowops.workflow.CreateWorkflowRequest;
import com.flowops.workflow.PublishWorkflowRequest;
import com.flowops.workflow.WorkflowService;
import com.flowops.workflow.nodes.NodeRegistry;
import com.flowops.workflow.validation.ValidationResult;
import com.flowops.workflow.validation.WorkflowValidator;
import com.flowops.billing.BillingPeriod;
import com.flowops.billing.model.EnforcementMode;
import com.flowops.billing.model.ExecutionReservationTicket;
import com.flowops.billing.model.ReservationResult;
import com.flowops.billing.service.BillingNoticeMailer;
import com.flowops.billing.service.BillingPeriodService;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.RejectedExecutionRecorder;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.config.ExecutionProperties;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowVersion;
import com.flowops.execution.ExecutionService;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import com.flowops.notification.NotificationService;
import com.flowops.repository.InvitationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.api.ExecutionDetailResponse;
import com.flowops.execution.RunWorkflowRequest;
import com.flowops.common.error.ApiErrorResponse;
import com.flowops.domain.WorkflowExecution;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class BillingEnforcementTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
    private static final FlowOpsPrincipal PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_ID, Role.MEMBER, "member@vantis.ai");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Mock
    private EntitlementService entitlementService;

    @Mock
    private RejectedExecutionRecorder rejectedRecorder;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowVersionRepository versionRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("ExecutionService blocks execution creation and records rejected execution when billing quota is reached")
    void executionServiceBlocksWhenQuotaReached() {
        UUID workflowId = UUID.randomUUID();
        ObjectNode emptyGraph = MAPPER.createObjectNode();
        Workflow workflow = Workflow.create(ORG_ID, USER_ID, "Test", "Desc", emptyGraph);
        workflow.markPublished(1);

        WorkflowVersion version = WorkflowVersion.create(
                workflow.getId(), 1, emptyGraph, "initial", USER_ID);

        when(workflowRepository.findByIdAndOrganizationId(workflowId, ORG_ID))
                .thenReturn(Optional.of(workflow));
        when(versionRepository.findByWorkflowIdAndVersionNumber(workflow.getId(), 1))
                .thenReturn(Optional.of(version));

        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Execution limit reached"))
                .when(entitlementService).reserveExecution(ORG_ID);

        ExecutionService executionService = new ExecutionService(
                workflowRepository,
                versionRepository,
                executionRepository,
                mock(com.flowops.repository.ExecutionNodeRepository.class),
                mock(com.flowops.repository.ExecutionLogRepository.class),
                mock(com.flowops.execution.QueueService.class),
                mock(com.flowops.execution.ExecutionEvents.class),
                new ExecutionProperties(4, 3, Duration.ofSeconds(2), Duration.ofSeconds(30), 300, Duration.ofSeconds(5), Duration.ofSeconds(30), 1048576, Duration.ofMinutes(30)),
                new NodeRegistry(),
                MAPPER,
                mock(com.flowops.repository.IdempotencyKeyRepository.class),
                entitlementService,
                rejectedRecorder);

        assertThatThrownBy(() -> executionService.run(PRINCIPAL, workflowId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(executionRepository, never()).save(any());
        verify(rejectedRecorder).recordRejected(eq(ORG_ID), eq(workflow.getId()), eq(version.getId()), eq(1), any(), eq(USER_ID), eq("PLAN_LIMIT_EXCEEDED"), any());
    }

    @Test
    @DisplayName("Two concurrent same-key requests: exactly one run, extra reservation refunded, usage incremented once")
    void concurrentSameKeyRequestsRefundsExtraReservationAndReturnsWinningRun() {
        UUID workflowId = UUID.randomUUID();
        ObjectNode emptyGraph = MAPPER.createObjectNode();
        Workflow workflow = Workflow.create(ORG_ID, USER_ID, "Test WF", "Desc", emptyGraph);
        workflow.markPublished(1);
        WorkflowVersion version = WorkflowVersion.create(workflow.getId(), 1, emptyGraph, "v1", USER_ID);

        when(workflowRepository.findByIdAndOrganizationId(workflowId, ORG_ID)).thenReturn(Optional.of(workflow));
        when(versionRepository.findByWorkflowIdAndVersionNumber(workflow.getId(), 1)).thenReturn(Optional.of(version));

        com.flowops.repository.IdempotencyKeyRepository idempRepo = mock(com.flowops.repository.IdempotencyKeyRepository.class);
        ExecutionReservationTicket ticket1 = ExecutionReservationTicket.reserved(Instant.now());
        ExecutionReservationTicket ticket2 = ExecutionReservationTicket.reserved(Instant.now());

        when(entitlementService.reserveExecution(ORG_ID)).thenReturn(ticket1, ticket2);

        ExecutionService executionService = new ExecutionService(
                workflowRepository,
                versionRepository,
                executionRepository,
                mock(com.flowops.repository.ExecutionNodeRepository.class),
                mock(com.flowops.repository.ExecutionLogRepository.class),
                mock(com.flowops.execution.QueueService.class),
                mock(com.flowops.execution.ExecutionEvents.class),
                new ExecutionProperties(4, 3, Duration.ofSeconds(2), Duration.ofSeconds(30), 300, Duration.ofSeconds(5), Duration.ofSeconds(30), 1048576, Duration.ofMinutes(30)),
                new NodeRegistry(),
                MAPPER,
                idempRepo,
                entitlementService,
                rejectedRecorder);

        String idempKey = "concurrent-key-123";
        String fullKey = "run:" + workflow.getId() + ":" + idempKey;
        ObjectNode inputWithKey = MAPPER.createObjectNode();
        inputWithKey.put("idempotencyKey", idempKey);
        RunWorkflowRequest runReq = new RunWorkflowRequest(inputWithKey);

        // Request 1: succeeds and saves idempotency key
        ExecutionDetailResponse exec1 = executionService.run(PRINCIPAL, workflowId, runReq);
        assertThat(exec1).isNotNull();

        // Request 2 (simulating concurrent race): insertIfNotExists returns 0 (already exists)
        when(idempRepo.insertIfNotExists(any(), any(), any(), any(), any())).thenReturn(0);
        when(idempRepo.findByKey(fullKey)).thenReturn(Optional.empty(), Optional.of(com.flowops.domain.IdempotencyKey.create(fullKey, exec1.id(), Duration.ofHours(24))));
        org.mockito.ArgumentCaptor<WorkflowExecution> execCaptor = org.mockito.ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        WorkflowExecution realExec1 = execCaptor.getValue();
        when(executionRepository.findById(exec1.id())).thenReturn(Optional.of(realExec1));

        ExecutionDetailResponse exec2 = executionService.run(PRINCIPAL, workflowId, runReq);

        // Exactly one run returned
        assertThat(exec2.id()).isEqualTo(exec1.id());

        // Extra reservation from Request 2 was refunded: net usage incremented exactly once!
        verify(entitlementService).refundExecution(eq(ORG_ID), eq(ticket2));
    }

    @Test
    @DisplayName("WorkflowService permits draft creation but blocks first-time publish when plan limit reached")
    void workflowServiceAllowsDraftButBlocksFirstPublish() {
        WorkflowValidator validator = mock(WorkflowValidator.class);
        when(validator.validate(any())).thenReturn(ValidationResult.of(List.of()));

        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Active workflow limit reached"))
                .when(entitlementService).assertCanActivateWorkflow(ORG_ID);

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                versionRepository,
                validator,
                MAPPER,
                mock(WebhookEventDispatcher.class),
                entitlementService,
                mock(com.flowops.repository.OrganizationRepository.class));

        // 1. Draft creation is ungated
        Workflow draft = Workflow.create(ORG_ID, USER_ID, "Draft WF", "Description", MAPPER.createObjectNode());
        when(workflowRepository.findByIdAndOrganizationId(draft.getId(), ORG_ID))
                .thenReturn(Optional.of(draft));

        // 2. First-time publish is gated and blocks
        assertThatThrownBy(() -> workflowService.publish(PRINCIPAL, draft.getId(), new PublishWorkflowRequest("v1")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(entitlementService).assertCanActivateWorkflow(ORG_ID);
        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("AiController blocks AI generation when quota reached")
    void aiControllerBlocksWhenQuotaReached() {
        AiServiceClient aiService = mock(AiServiceClient.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);

        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "AI limit reached"))
                .when(entitlementService).reserveAiGeneration(ORG_ID);

        AiController controller = new AiController(
                aiService,
                new NodeRegistry(),
                MAPPER,
                rateLimiter,
                entitlementService);

        assertThatThrownBy(() -> controller.generate(new GenerateWorkflowRequest("Create invoice parser")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(aiService, never()).generateWorkflow(any());
        verify(entitlementService, never()).refundAiGeneration(any(), any());
    }

    @Test
    @DisplayName("AiController refunds quota upon unconfigured or failed generation")
    void aiControllerRefundsQuotaWhenUnconfigured() {
        AiServiceClient aiService = mock(AiServiceClient.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);

        ExecutionReservationTicket ticket = ExecutionReservationTicket.reserved(java.time.Instant.now());
        when(entitlementService.reserveAiGeneration(ORG_ID)).thenReturn(ticket);

        when(aiService.generateWorkflow("Build ETL flow"))
                .thenReturn(new AiServiceClient.Generation(false, null, null));

        AiController controller = new AiController(
                aiService,
                new NodeRegistry(),
                MAPPER,
                rateLimiter,
                entitlementService);

        assertThatThrownBy(() -> controller.generate(new GenerateWorkflowRequest("Build ETL flow")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED));

        verify(entitlementService).reserveAiGeneration(ORG_ID);
        verify(entitlementService).refundAiGeneration(ORG_ID, ticket);
    }

    @Test
    @DisplayName("LOG mode over the cap + failed execution creation must not decrement the usage counter")
    void logModeOverCapDoesNotRefundWhenUnreserved() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        NotificationService notifService = mock(NotificationService.class);
        BillingNoticeMailer mailer = mock(BillingNoticeMailer.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, null, MAPPER, null, "log");

        Subscription sub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(sub));
        when(periodService.resolvePeriod(sub)).thenReturn(new BillingPeriod(sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd()));

        // In LOG mode, meter counts past the cap (Long.MAX_VALUE), does not block
        when(usageMeter.reserveExecution(eq(ORG_ID), any(), any(), eq(Long.MAX_VALUE)))
                .thenReturn(new ReservationResult(true, true, (long) com.flowops.billing.model.PlanCatalog.FREE_EXECUTIONS_PER_MONTH + 10, Long.MAX_VALUE));

        ExecutionReservationTicket ticket = entitlement.reserveExecution(ORG_ID);
        assertThat(ticket.reserved()).isTrue();

        // An unreserved ticket (e.g. from fail-open) never triggers a refund
        ExecutionReservationTicket unreservedTicket = ExecutionReservationTicket.notReserved();
        entitlement.refundExecution(ORG_ID, unreservedTicket);
        verify(usageMeter, never()).refundExecution(any(), any());
    }

    @Test
    @DisplayName("EntitlementService fails open and increments labeled metric counter for all four causes: missing_subscription, unknown_plan (including null plan id), db_error, and redis_error")
    void entitlementServiceFailsOpenGracefully() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);
        io.micrometer.core.instrument.MeterRegistry meterRegistry = mock(io.micrometer.core.instrument.MeterRegistry.class);
        io.micrometer.core.instrument.Counter missingSubCounter = mock(io.micrometer.core.instrument.Counter.class);
        io.micrometer.core.instrument.Counter unknownPlanCounter = mock(io.micrometer.core.instrument.Counter.class);
        io.micrometer.core.instrument.Counter dbErrorCounter = mock(io.micrometer.core.instrument.Counter.class);
        io.micrometer.core.instrument.Counter redisErrorCounter = mock(io.micrometer.core.instrument.Counter.class);

        when(meterRegistry.counter("entitlements.fail_open", "reason", "missing_subscription")).thenReturn(missingSubCounter);
        when(meterRegistry.counter("entitlements.fail_open", "reason", "unknown_plan")).thenReturn(unknownPlanCounter);
        when(meterRegistry.counter("entitlements.fail_open", "reason", "db_error")).thenReturn(dbErrorCounter);
        when(meterRegistry.counter("entitlements.fail_open", "reason", "redis_error")).thenReturn(redisErrorCounter);

        org.springframework.data.redis.core.StringRedisTemplate redisTemplate = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, redisTemplate, MAPPER, meterRegistry, "enforce");

        // 1. Missing subscription row -> fail open (action allowed, metric labeled missing_subscription)
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.empty());
        ExecutionReservationTicket missingSubTicket = entitlement.reserveExecution(ORG_ID);
        assertThat(missingSubTicket.reserved()).isFalse();
        verify(missingSubCounter).increment();
        assertThat(entitlement.canActivateWorkflow(ORG_ID)).isTrue();

        // 2a. Unknown plan ID -> fail open (action allowed, metric labeled unknown_plan)
        Subscription unknownPlanSub = Subscription.create(
                ORG_ID, "NON_EXISTENT_PLAN_XYZ", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(unknownPlanSub));
        ExecutionReservationTicket unknownPlanTicket = entitlement.reserveExecution(ORG_ID);
        assertThat(unknownPlanTicket.reserved()).isFalse();
        verify(unknownPlanCounter).increment();

        // 2b. Null plan ID -> fail open (action allowed, metric labeled unknown_plan)
        Subscription nullPlanSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400));
        nullPlanSub.setPlan(null);
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(nullPlanSub));
        ExecutionReservationTicket nullPlanTicket = entitlement.reserveExecution(ORG_ID);
        assertThat(nullPlanTicket.reserved()).isFalse();
        verify(unknownPlanCounter, org.mockito.Mockito.times(2)).increment();
        entitlement.assertCanActivateWorkflow(ORG_ID);

        // 3. Database error -> fail open (action allowed, metric labeled db_error)
        when(subRepo.findByWorkspaceId(ORG_ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection timeout"));
        ExecutionReservationTicket dbErrorTicket = entitlement.reserveExecution(ORG_ID);
        assertThat(dbErrorTicket.reserved()).isFalse();
        verify(dbErrorCounter).increment();
        assertThat(entitlement.canActivateWorkflow(ORG_ID)).isTrue();

        // 4. Redis error -> fail open to DB (action allowed, metric labeled redis_error)
        Subscription validSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400));
        doReturn(Optional.of(validSub)).when(subRepo).findByWorkspaceId(ORG_ID);
        when(periodService.resolvePeriod(validSub)).thenReturn(new BillingPeriod(validSub.getCurrentPeriodStart(), validSub.getCurrentPeriodEnd()));
        when(valueOps.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis connection refused"));
        // When redis throws on read, resolvePlanContext falls open to DB and logs/increments redis_error
        var resolvedCtx = entitlement.resolvePlanContext(ORG_ID);
        assertThat(resolvedCtx).isPresent();
        verify(redisErrorCounter).increment();
    }

    @Test
    @DisplayName("Lifetime AI investigation quota: Free plan uses lifetime trial, Pro uses monthly allowance")
    void lifetimeTrialVsMonthlyAllowanceInvestigationQuota() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, null, MAPPER, null, "enforce");

        // 1. Free plan (monthly investigations = 0) -> uses lifetime trial counter (2 limit)
        Subscription freeSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(freeSub));
        when(periodService.resolvePeriod(freeSub)).thenReturn(new BillingPeriod(freeSub.getCurrentPeriodStart(), freeSub.getCurrentPeriodEnd()));

        int trialLimit = com.flowops.billing.model.PlanCatalog.FREE_AI_INVESTIGATIONS_LIFETIME_TRIAL;
        when(usageMeter.reserveAiInvestigationTrial(ORG_ID, trialLimit))
                .thenReturn(new ReservationResult(true, true, 1L, (long) trialLimit));

        EntitlementService.InvestigationReservationTicket freeTicket = entitlement.reserveAiInvestigation(ORG_ID);
        assertThat(freeTicket.reserved()).isTrue();
        assertThat(freeTicket.isTrial()).isTrue();
        verify(usageMeter).reserveAiInvestigationTrial(ORG_ID, trialLimit);
        verify(usageMeter, never()).reserveAiInvestigationMonthly(any(), any(), any(), any(Integer.class));

        // Refund free plan trial
        entitlement.refundAiInvestigation(ORG_ID, freeTicket);
        verify(usageMeter).refundAiInvestigationTrial(ORG_ID);

        // 2. Pro plan (monthly investigations = 50) -> uses monthly counter
        Subscription proSub = Subscription.create(
                ORG_ID, "PRO", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(proSub));
        when(periodService.resolvePeriod(proSub)).thenReturn(new BillingPeriod(proSub.getCurrentPeriodStart(), proSub.getCurrentPeriodEnd()));

        long monthlyLimit = com.flowops.billing.model.PlanCatalog.PRO_AI_INVESTIGATIONS_MONTHLY;
        when(usageMeter.reserveAiInvestigationMonthly(eq(ORG_ID), any(), any(), eq(monthlyLimit)))
                .thenReturn(new ReservationResult(true, true, 1L, monthlyLimit));

        EntitlementService.InvestigationReservationTicket proTicket = entitlement.reserveAiInvestigation(ORG_ID);
        assertThat(proTicket.reserved()).isTrue();
        assertThat(proTicket.isTrial()).isFalse();
        verify(usageMeter).reserveAiInvestigationMonthly(eq(ORG_ID), any(), any(), eq(monthlyLimit));

        // Refund pro plan monthly
        entitlement.refundAiInvestigation(ORG_ID, proTicket);
        verify(usageMeter).refundAiInvestigationMonthly(eq(ORG_ID), any());
    }

    @Test
    @DisplayName("Grandfathered workflows: org with 12 active workflows under a 10 cap allows republish, but blocks 13th activation")
    void grandfatheredWorkflowsCanRepublishWithoutEntitlementBlock() {
        WorkflowValidator validator = mock(WorkflowValidator.class);
        when(validator.validate(any())).thenReturn(ValidationResult.of(List.of()));
        com.flowops.repository.OrganizationRepository orgRepo = mock(com.flowops.repository.OrganizationRepository.class);

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                versionRepository,
                validator,
                MAPPER,
                mock(WebhookEventDispatcher.class),
                entitlementService,
                orgRepo);

        // Case 1: Grandfathered existing workflow (already published, status = PUBLISHED)
        Workflow grandfatheredWf = Workflow.create(ORG_ID, USER_ID, "Grandfathered WF", "Desc", MAPPER.createObjectNode());
        grandfatheredWf.markPublished(1);

        when(workflowRepository.findByIdAndOrganizationId(grandfatheredWf.getId(), ORG_ID))
                .thenReturn(Optional.of(grandfatheredWf));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Publishing an update to existing published workflow is allowed and never checks activation quota
        var pubResult = workflowService.publish(PRINCIPAL, grandfatheredWf.getId(), new PublishWorkflowRequest("v2 update"));
        assertThat(pubResult.version()).isNotNull();
        verify(entitlementService, never()).assertCanActivateWorkflow(any());

        // Case 2: 13th activation (a new workflow in DRAFT status)
        Workflow draftWf = Workflow.create(ORG_ID, USER_ID, "Draft WF 13", "Desc", MAPPER.createObjectNode());
        when(workflowRepository.findByIdAndOrganizationId(draftWf.getId(), ORG_ID))
                .thenReturn(Optional.of(draftWf));
        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Active workflow limit reached"))
                .when(entitlementService).assertCanActivateWorkflow(ORG_ID);

        assertThatThrownBy(() -> workflowService.publish(PRINCIPAL, draftWf.getId(), new PublishWorkflowRequest("v1 initial")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(entitlementService).assertCanActivateWorkflow(ORG_ID);
    }

    @Test
    @DisplayName("Two concurrent activations at cap-1 allow exactly one to publish")
    void concurrentActivationsAtCapMinusOneAllowsExactlyOne() {
        WorkflowValidator validator = mock(WorkflowValidator.class);
        when(validator.validate(any())).thenReturn(ValidationResult.of(List.of()));
        com.flowops.repository.OrganizationRepository orgRepo = mock(com.flowops.repository.OrganizationRepository.class);

        // Cap is 3 on FREE
        long cap = com.flowops.billing.model.PlanCatalog.FREE_ACTIVE_WORKFLOWS;
        // Currently at cap - 1 (2 active workflows)
        java.util.concurrent.atomic.AtomicLong activeCounter = new java.util.concurrent.atomic.AtomicLong(cap - 1);

        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        WorkflowVersionRepository verRepo = mock(WorkflowVersionRepository.class);
        when(verRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntitlementService entitlement = mock(EntitlementService.class);
        // Synchronized entitlement check simulating org row lock serialization: first caller increments to cap (allowed), second sees cap (throws)
        org.mockito.Mockito.doAnswer(inv -> {
            long current = activeCounter.getAndIncrement();
            if (current >= cap) {
                throw ApiException.planLimitExceeded("activeWorkflows", current, cap, "FREE", "PRO", "Limit reached");
            }
            return null;
        }).when(entitlement).assertCanActivateWorkflow(ORG_ID);

        WorkflowService service = new WorkflowService(
                wfRepo, verRepo, validator, MAPPER, mock(WebhookEventDispatcher.class), entitlement, orgRepo);

        Workflow wf1 = Workflow.create(ORG_ID, USER_ID, "Draft 1", "Desc", MAPPER.createObjectNode());
        Workflow wf2 = Workflow.create(ORG_ID, USER_ID, "Draft 2", "Desc", MAPPER.createObjectNode());

        when(wfRepo.findByIdAndOrganizationId(wf1.getId(), ORG_ID)).thenReturn(Optional.of(wf1));
        when(wfRepo.findByIdAndOrganizationId(wf2.getId(), ORG_ID)).thenReturn(Optional.of(wf2));

        // Call 1 succeeds
        var res1 = service.publish(PRINCIPAL, wf1.getId(), new PublishWorkflowRequest("v1"));
        assertThat(res1.published()).isTrue();

        // Call 2 fails with PLAN_LIMIT_EXCEEDED
        assertThatThrownBy(() -> service.publish(PRINCIPAL, wf2.getId(), new PublishWorkflowRequest("v1")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        // Exactly one org row lock acquired per activation check
        verify(orgRepo, org.mockito.Mockito.times(2)).findByIdWithLock(ORG_ID);
    }

    @Test
    @DisplayName("Limit boundaries: execution reservations succeed under limit and at limit, and fail when over limit")
    void limitBoundariesExecutionQuotaEnforcement() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, null, MAPPER, null, "enforce");

        Subscription freeSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(freeSub));
        when(periodService.resolvePeriod(freeSub)).thenReturn(new BillingPeriod(freeSub.getCurrentPeriodStart(), freeSub.getCurrentPeriodEnd()));

        long freeLimit = com.flowops.billing.model.PlanCatalog.FREE_EXECUTIONS_PER_MONTH;

        // 1. Boundary: under limit (freeLimit - 1)
        when(usageMeter.reserveExecution(eq(ORG_ID), any(), any(), eq(freeLimit)))
                .thenReturn(new ReservationResult(true, true, freeLimit - 1, freeLimit));
        ExecutionReservationTicket ticketUnder = entitlement.reserveExecution(ORG_ID);
        assertThat(ticketUnder.reserved()).isTrue();

        // 2. Boundary: exactly at limit
        when(usageMeter.reserveExecution(eq(ORG_ID), any(), any(), eq(freeLimit)))
                .thenReturn(new ReservationResult(true, true, freeLimit, freeLimit));
        ExecutionReservationTicket ticketAt = entitlement.reserveExecution(ORG_ID);
        assertThat(ticketAt.reserved()).isTrue();

        // 3. Boundary: rejected when over limit
        when(usageMeter.reserveExecution(eq(ORG_ID), any(), any(), eq(freeLimit)))
                .thenReturn(new ReservationResult(false, false, freeLimit, freeLimit));
        assertThatThrownBy(() -> entitlement.reserveExecution(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(((ApiException) e).details()).isNotNull();
                    assertThat(((ApiException) e).details().limitKey()).isEqualTo("executionsPerMonth");
                    assertThat(((ApiException) e).details().max()).isEqualTo(freeLimit);
                    assertThat(((ApiException) e).details().planId()).isEqualTo("FREE");
                    assertThat(((ApiException) e).details().suggestedPlan()).isEqualTo("PRO");
                });
    }

    @Test
    @DisplayName("Limit boundaries: workflow and member limit boundaries block strictly at limit")
    void limitBoundariesWorkflowAndMemberQuotas() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, null, MAPPER, null, "enforce");

        Subscription freeSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(freeSub));
        when(periodService.resolvePeriod(freeSub)).thenReturn(new BillingPeriod(freeSub.getCurrentPeriodStart(), freeSub.getCurrentPeriodEnd()));

        long wfLimit = com.flowops.billing.model.PlanCatalog.FREE_ACTIVE_WORKFLOWS;
        long seatLimit = com.flowops.billing.model.PlanCatalog.FREE_SEATS;

        // Workflow boundary: allowed at wfLimit - 1, blocked at wfLimit
        when(wfRepo.countActiveByOrganizationId(ORG_ID)).thenReturn(wfLimit - 1);
        entitlement.assertCanActivateWorkflow(ORG_ID); // Allowed

        when(wfRepo.countActiveByOrganizationId(ORG_ID)).thenReturn(wfLimit);
        assertThatThrownBy(() -> entitlement.assertCanActivateWorkflow(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(((ApiException) e).details().limitKey()).isEqualTo("activeWorkflows");
                    assertThat(((ApiException) e).details().max()).isEqualTo(wfLimit);
                });

        // Team member boundary: blocked at seatLimit
        when(memberRepo.countByOrganizationId(ORG_ID)).thenReturn(seatLimit);
        when(invRepo.countByOrganizationIdAndStatusAndExpiresAtAfter(eq(ORG_ID), any(), any())).thenReturn(0L);
        assertThatThrownBy(() -> entitlement.assertCanAddMember(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(((ApiException) e).details().limitKey()).isEqualTo("seats");
                    assertThat(((ApiException) e).details().max()).isEqualTo(seatLimit);
                });
    }

    @Test
    @DisplayName("Structured plan limit exceeded error payload serializes machine-readable details")
    void structuredPlanLimitErrorPayloadSerializesMachineReadableDetails() throws Exception {
        ApiException ex = ApiException.planLimitExceeded(
                "executionsPerMonth", 1000, 1000, "FREE", "PRO", "Monthly execution limit reached.");

        ApiErrorResponse response = ApiErrorResponse.of(
                ex.code(), ex.getMessage(), "/executions", "req-123", null, ex.details());

        String json = MAPPER.writeValueAsString(response);
        var tree = MAPPER.readTree(json);
        var error = tree.get("error");

        assertThat(error.get("code").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(error.get("details")).isNotNull();
        assertThat(error.get("details").get("limitKey").asText()).isEqualTo("executionsPerMonth");
        assertThat(error.get("details").get("current").asLong()).isEqualTo(1000L);
        assertThat(error.get("details").get("max").asLong()).isEqualTo(1000L);
        assertThat(error.get("details").get("planId").asText()).isEqualTo("FREE");
        assertThat(error.get("details").get("suggestedPlan").asText()).isEqualTo("PRO");
    }

    @Test
    @DisplayName("Private Beta expired treated as FREE limits for new activations and runs without deleting data")
    void privateBetaExpiredTreatedAsFreeLimits() {
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationMemberRepository memberRepo = mock(OrganizationMemberRepository.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, wfRepo, invRepo, memberRepo, null, null, MAPPER, null, "enforce");

        // Subscription created 50 days ago in PRIVATE_BETA (beta expired 5 days ago)
        Subscription betaSub = Subscription.create(
                ORG_ID, "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now().minus(50, java.time.temporal.ChronoUnit.DAYS),
                java.time.Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS));
        betaSub.setBetaStartedAt(java.time.Instant.now().minus(50, java.time.temporal.ChronoUnit.DAYS));
        betaSub.setBetaEndsAt(java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.DAYS));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(betaSub));
        when(periodService.resolvePeriod(betaSub)).thenReturn(new BillingPeriod(betaSub.getCurrentPeriodStart(), betaSub.getCurrentPeriodEnd()));

        var ctx = entitlement.resolvePlanContext(ORG_ID);
        assertThat(ctx).isPresent();
        assertThat(ctx.get().betaExpired()).isTrue();
        // Limits must be FREE limits (3 workflows, 1000 executions), NOT PRO limits
        assertThat(ctx.get().workflowLimit()).isEqualTo(com.flowops.billing.model.PlanCatalog.FREE_ACTIVE_WORKFLOWS);
        assertThat(ctx.get().executionLimit()).isEqualTo(com.flowops.billing.model.PlanCatalog.FREE_EXECUTIONS_PER_MONTH);
    }

    @Test
    @DisplayName("In-flight execution failures alert regardless of quota cap, whereas quota-rejected runs fire zero alerts")
    void inFlightFailureAlertsAtCapVsRejectedRunZeroAlerts() {
        NotificationService notifService = mock(NotificationService.class);

        // Scenario 1: An in-flight execution fails (e.g. timeout on 500th allowed run)
        // NotificationService.publish is invoked independently via REQUIRES_NEW
        notifService.publish(ORG_ID, USER_ID, com.flowops.domain.NotificationLevel.ERROR,
                "Workflow Execution Failed", "Step timed out", "/executions/123");
        verify(notifService).publish(eq(ORG_ID), eq(USER_ID), eq(com.flowops.domain.NotificationLevel.ERROR), any(), any(), any());

        // Scenario 2: A quota-rejected run (rejectedRecorder.recordRejected)
        // Verified: It only saves a rejected row with rejection_reason and does NOT call notifService or incident creation
        RejectedExecutionRecorder recorder = mock(RejectedExecutionRecorder.class);
        recorder.recordRejected(ORG_ID, UUID.randomUUID(), UUID.randomUUID(), 1,
                com.flowops.domain.TriggerType.MANUAL, USER_ID, "PLAN_LIMIT_EXCEEDED", "Quota cap reached");

        verify(recorder).recordRejected(eq(ORG_ID), any(), any(), eq(1), any(), eq(USER_ID), eq("PLAN_LIMIT_EXCEEDED"), any());
        // Confirm no further alert or incident service is invoked for quota rejections
    }

    @Test
    @DisplayName("Failure alerts are never blocked on any plan at 100% of quota")
    void failureAlertsNeverBlockedAt100PercentQuota() {
        // Even when an org has exhausted 100% of its execution quota (1000/1000 on FREE)
        NotificationService notifService = mock(NotificationService.class);
        UsageMeterService usageMeter = mock(UsageMeterService.class);
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        BillingPeriodService periodService = mock(BillingPeriodService.class);

        EntitlementService entitlement = new EntitlementService(
                subRepo, usageMeter, periodService, null, null, null, null, null, MAPPER, null, "enforce");

        Subscription freeSub = Subscription.create(
                ORG_ID, "FREE", "ACTIVE", "USD", "US", "MONTHLY",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 30));
        when(subRepo.findByWorkspaceId(ORG_ID)).thenReturn(Optional.of(freeSub));
        when(periodService.resolvePeriod(freeSub)).thenReturn(new BillingPeriod(freeSub.getCurrentPeriodStart(), freeSub.getCurrentPeriodEnd()));

        long freeLimit = com.flowops.billing.model.PlanCatalog.FREE_EXECUTIONS_PER_MONTH;
        // 100% of quota reached
        when(usageMeter.reserveExecution(eq(ORG_ID), any(), any(), eq(freeLimit)))
                .thenReturn(new ReservationResult(false, false, freeLimit, freeLimit));

        // Execution is blocked due to 100% quota
        assertThatThrownBy(() -> entitlement.reserveExecution(ORG_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        // BUT failure alert delivery is NEVER blocked: NotificationService & alerts proceed unhindered
        notifService.publish(
                ORG_ID,
                USER_ID,
                com.flowops.domain.NotificationLevel.ERROR,
                "Workflow Failure Alert",
                "Critical step failed",
                "/executions/999");

        verify(notifService).publish(eq(ORG_ID), eq(USER_ID), eq(com.flowops.domain.NotificationLevel.ERROR), any(), any(), any());
    }

    @Test
    @DisplayName("EnforcementMode.fromString defaults to LOG on null, empty, or unrecognized typos")
    void enforcementModeFromStringDefaultsToLog() {
        assertThat(EnforcementMode.fromString(null)).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("   ")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("unknown")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("typo_mode")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("off")).isEqualTo(EnforcementMode.OFF);
        assertThat(EnforcementMode.fromString("OFF")).isEqualTo(EnforcementMode.OFF);
        assertThat(EnforcementMode.fromString("log")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("LOG")).isEqualTo(EnforcementMode.LOG);
        assertThat(EnforcementMode.fromString("enforce")).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(EnforcementMode.fromString("ENFORCE")).isEqualTo(EnforcementMode.ENFORCE);
    }
}
