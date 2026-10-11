package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.service.BillingLifecycleScheduler;
import com.flowops.billing.service.BillingNoticeService;
import com.flowops.billing.service.SubscriptionLifecycleService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.BillingAuditLog;
import com.flowops.domain.BillingDiscount;
import com.flowops.domain.BillingNotice;
import com.flowops.domain.ExecutionStatus;
import com.flowops.domain.Notification;
import com.flowops.domain.Organization;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.domain.TriggerType;
import com.flowops.domain.UserAccount;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowExecution;
import com.flowops.domain.WorkflowStatus;
import com.flowops.domain.WorkflowVersion;
import com.flowops.execution.ExecutionService;
import com.flowops.repository.BillingAuditLogRepository;
import com.flowops.repository.BillingDiscountRepository;
import com.flowops.repository.BillingNoticeRepository;
import com.flowops.repository.NotificationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class BillingSubscriptionLifecycleServiceTest {

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SubscriptionLifecycleService lifecycleService;

    @Autowired
    private BillingLifecycleScheduler lifecycleScheduler;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private BillingDiscountRepository discountRepository;

    @Autowired
    private BillingAuditLogRepository auditLogRepository;

    @Autowired
    private BillingNoticeRepository noticeRepository;

    @Autowired
    private BillingNoticeService billingNoticeService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMemberRepository memberRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization org1;
    private Organization org2;
    private UserAccount ownerUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        cleanData();

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        java.util.concurrent.atomic.AtomicReference<String> heldLock = new java.util.concurrent.atomic.AtomicReference<>(null);

        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenAnswer(inv -> {
            String token = inv.getArgument(1);
            return heldLock.compareAndSet(null, token);
        });

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenAnswer(inv -> {
            String token = inv.getArgument(2);
            if (token != null && token.equals(heldLock.get())) {
                heldLock.set(null);
                return 1L;
            }
            return 0L;
        });

        org1 = organizationRepository.save(Organization.create("Org Lifecycle 1", "org-life-1"));
        org2 = organizationRepository.save(Organization.create("Org Lifecycle 2", "org-life-2"));
        ownerUser = userRepository.save(UserAccount.create("owner-" + UUID.randomUUID() + "@test.local", "hashedpw", "Test Owner"));
        userId = ownerUser.getId();
        memberRepository.save(OrganizationMember.create(userId, org1.getId(), Role.OWNER));
        memberRepository.save(OrganizationMember.create(userId, org2.getId(), Role.OWNER));
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        notificationRepository.deleteAll();
        noticeRepository.deleteAll();
        auditLogRepository.deleteAll();
        discountRepository.deleteAll();
        executionRepository.deleteAll();
        versionRepository.deleteAll();
        workflowRepository.deleteAll();
        subscriptionRepository.deleteAll();
        memberRepository.deleteAll();
        if (ownerUser != null) {
            userRepository.deleteById(ownerUser.getId());
        }
        organizationRepository.deleteAll();
    }

    // =========================================================================
    // 1. Beta Expiry Downgrade
    // =========================================================================

    @Test
    @DisplayName("Beta expiry downgrades organization to FREE, sets status ACTIVE, and records audit log")
    void betaExpiryDowngrade() {
        Instant past = Instant.now().minus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant betaEnded = past.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", past, betaEnded);
        sub.setBetaStartedAt(past);
        sub.setBetaEndsAt(betaEnded);
        subscriptionRepository.save(sub);

        int processed = lifecycleService.expireBetaSubscriptions();
        assertThat(processed).isEqualTo(1);

        Subscription updated = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(updated.getPlan()).isEqualTo("FREE");
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");

        List<BillingAuditLog> auditLogs = auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId());
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("BETA_EXPIRED_DOWNGRADE");
        assertThat(auditLogs.get(0).getFromPlan()).isEqualTo("PRIVATE_BETA");
        assertThat(auditLogs.get(0).getToPlan()).isEqualTo("FREE");
    }

    // =========================================================================
    // 2. Repeated Beta Expiry Execution is Idempotent
    // =========================================================================

    @Test
    @DisplayName("Repeated beta expiry execution produces the same final state without duplicate changes")
    void repeatedBetaExpiryExecutionIsIdempotent() {
        Instant past = Instant.now().minus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant betaEnded = past.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", past, betaEnded);
        sub.setBetaStartedAt(past);
        sub.setBetaEndsAt(betaEnded);
        subscriptionRepository.save(sub);

        lifecycleService.expireBetaSubscriptions();
        int repeated = lifecycleService.expireBetaSubscriptions();
        assertThat(repeated).isEqualTo(0);

        Subscription updated = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(updated.getPlan()).isEqualTo("FREE");
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId())).hasSize(1);
    }

    // =========================================================================
    // 3. Workflow Deactivation Keeps Oldest Workflows Active
    // =========================================================================

    @Test
    @DisplayName("Workflow limit enforcement keeps oldest-created workflows active and deactivates excess")
    void workflowDeactivationKeepsOldestWorkflowsActive() {
        List<Workflow> createdWorkflows = new ArrayList<>();
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        for (int i = 0; i < 5; i++) {
            Workflow wf = Workflow.create(org1.getId(), userId, "Workflow-" + i, "Desc " + i, objectMapper.createObjectNode());
            wf.markPublished(1);
            setCreatedAt(wf, baseTime.plus(i, ChronoUnit.HOURS));
            createdWorkflows.add(workflowRepository.save(wf));
        }

        int deactivated = lifecycleService.enforceWorkflowLimits(org1.getId(), 3);
        assertThat(deactivated).isEqualTo(2);

        List<Workflow> publishedAfter = workflowRepository.findPublishedByOrganizationIdOrderByCreatedAtAscIdAsc(org1.getId());
        assertThat(publishedAfter).hasSize(3);

        // Kept active: oldest 3
        assertThat(publishedAfter.get(0).getId()).isEqualTo(createdWorkflows.get(0).getId());
        assertThat(publishedAfter.get(1).getId()).isEqualTo(createdWorkflows.get(1).getId());
        assertThat(publishedAfter.get(2).getId()).isEqualTo(createdWorkflows.get(2).getId());

        // Deactivated to DRAFT: newest 2
        Workflow wf3 = workflowRepository.findById(createdWorkflows.get(3).getId()).orElseThrow();
        Workflow wf4 = workflowRepository.findById(createdWorkflows.get(4).getId()).orElseThrow();
        assertThat(wf3.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(wf4.getStatus()).isEqualTo(WorkflowStatus.DRAFT);

        // Never deleted
        assertThat(workflowRepository.findAll()).hasSize(5);
    }

    // =========================================================================
    // 4. Workflow Deactivation is Idempotent
    // =========================================================================

    @Test
    @DisplayName("Repeated workflow limit enforcement is idempotent and leaves active workflows unchanged")
    void workflowDeactivationIsIdempotent() {
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < 4; i++) {
            Workflow wf = Workflow.create(org1.getId(), userId, "Workflow-" + i, null, objectMapper.createObjectNode());
            wf.markPublished(1);
            setCreatedAt(wf, baseTime.plus(i, ChronoUnit.HOURS));
            workflowRepository.save(wf);
        }

        lifecycleService.enforceWorkflowLimits(org1.getId(), 3);
        int repeatedDeactivated = lifecycleService.enforceWorkflowLimits(org1.getId(), 3);
        assertThat(repeatedDeactivated).isEqualTo(0);

        List<Workflow> published = workflowRepository.findPublishedByOrganizationIdOrderByCreatedAtAscIdAsc(org1.getId());
        assertThat(published).hasSize(3);
    }

    // =========================================================================
    // 5. 3-Day History Locking
    // =========================================================================

    @Test
    @DisplayName("Executions older than 3 days are locked and protected against mutation")
    void threeDayHistoryLocking() {
        Workflow wf = Workflow.create(org1.getId(), userId, "Test WF", null, objectMapper.createObjectNode());
        wf.markPublished(1);
        wf = workflowRepository.save(wf);

        WorkflowVersion version = WorkflowVersion.create(wf.getId(), 1, objectMapper.createObjectNode(), "v1", userId);
        version = versionRepository.save(version);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant fiveDaysAgo = now.minus(5, ChronoUnit.DAYS);
        Instant fourDaysAgo = now.minus(4, ChronoUnit.DAYS);
        Instant oneDayAgo = now.minus(1, ChronoUnit.DAYS);

        WorkflowExecution e1 = WorkflowExecution.create(org1.getId(), wf.getId(), version.getId(), 1, TriggerType.MANUAL, objectMapper.createObjectNode(), userId);
        e1.markFailed("Failure 1");
        setCreatedAt(e1, fiveDaysAgo);
        e1 = executionRepository.save(e1);

        WorkflowExecution e2 = WorkflowExecution.create(org1.getId(), wf.getId(), version.getId(), 1, TriggerType.MANUAL, objectMapper.createObjectNode(), userId);
        e2.markFailed("Failure 2");
        setCreatedAt(e2, fourDaysAgo);
        e2 = executionRepository.save(e2);

        WorkflowExecution e3 = WorkflowExecution.create(org1.getId(), wf.getId(), version.getId(), 1, TriggerType.MANUAL, objectMapper.createObjectNode(), userId);
        e3.markFailed("Failure 3");
        setCreatedAt(e3, oneDayAgo);
        e3 = executionRepository.save(e3);

        int locked = lifecycleService.lockExecutionHistory(org1.getId(), 3);
        assertThat(locked).isEqualTo(2);

        WorkflowExecution updatedE1 = executionRepository.findById(e1.getId()).orElseThrow();
        WorkflowExecution updatedE2 = executionRepository.findById(e2.getId()).orElseThrow();
        WorkflowExecution updatedE3 = executionRepository.findById(e3.getId()).orElseThrow();

        assertThat(updatedE1.isLocked()).isTrue();
        assertThat(updatedE1.getLockedAt()).isNotNull();
        assertThat(updatedE2.isLocked()).isTrue();
        assertThat(updatedE2.getLockedAt()).isNotNull();
        assertThat(updatedE3.isLocked()).isFalse();

        // Mutation protection
        FlowOpsPrincipal principal = new FlowOpsPrincipal(userId, UUID.randomUUID(), org1.getId(), Role.ADMIN, "admin@example.com");
        assertThatThrownBy(() -> executionService.retry(principal, updatedE1.getId(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.EXECUTION_NOT_RETRYABLE));

        // Repeated execution is idempotent
        int repeated = lifecycleService.lockExecutionHistory(org1.getId(), 3);
        assertThat(repeated).isEqualTo(0);
    }

    // =========================================================================
    // 6. Day 30 Notice
    // =========================================================================

    @Test
    @DisplayName("Day 30 notice is sent when 30 days have elapsed in private beta")
    void day30Notice() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant betaStart = now.minus(31, ChronoUnit.DAYS);
        Instant betaEnd = betaStart.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", betaStart, betaEnd);
        sub.setBetaStartedAt(betaStart);
        sub.setBetaEndsAt(betaEnd);
        subscriptionRepository.save(sub);

        int sent = lifecycleService.sendBetaExpiryWarnings();
        assertThat(sent).isEqualTo(1);

        List<BillingNotice> notices = noticeRepository.findAll();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getThreshold()).isEqualTo(30);
        assertThat(notices.get(0).getKind()).isEqualTo("BETA_EXPIRY_WARNING");
        assertThat(notificationRepository.findAll()).isNotEmpty();
    }

    // =========================================================================
    // 7. Day 40 Notice
    // =========================================================================

    @Test
    @DisplayName("Day 40 notice is sent when 40 days have elapsed in private beta")
    void day40Notice() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant betaStart = now.minus(41, ChronoUnit.DAYS);
        Instant betaEnd = betaStart.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", betaStart, betaEnd);
        sub.setBetaStartedAt(betaStart);
        sub.setBetaEndsAt(betaEnd);
        subscriptionRepository.save(sub);

        int sent = lifecycleService.sendBetaExpiryWarnings();
        assertThat(sent).isEqualTo(1);

        assertThat(noticeRepository.findByOrganizationIdAndPeriodStartAndThresholdAndKind(
                org1.getId(), betaStart, 40, "BETA_EXPIRY_WARNING")).isPresent();
    }

    // =========================================================================
    // 8. Day 44 Notice
    // =========================================================================

    @Test
    @DisplayName("Day 44 notice is sent when 44 days have elapsed (1 day remaining)")
    void day44Notice() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant betaStart = now.minus(44, ChronoUnit.DAYS);
        Instant betaEnd = betaStart.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", betaStart, betaEnd);
        sub.setBetaStartedAt(betaStart);
        sub.setBetaEndsAt(betaEnd);
        subscriptionRepository.save(sub);

        int sent = lifecycleService.sendBetaExpiryWarnings();
        assertThat(sent).isEqualTo(1);

        assertThat(noticeRepository.findByOrganizationIdAndPeriodStartAndThresholdAndKind(
                org1.getId(), betaStart, 44, "BETA_EXPIRY_WARNING")).isPresent();
    }

    // =========================================================================
    // 9. Each Notice is Sent Only Once
    // =========================================================================

    @Test
    @DisplayName("Each notice threshold is sent exactly once per organization and deduplicated persistently")
    void eachNoticeIsSentOnlyOnce() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant betaStart = now.minus(32, ChronoUnit.DAYS);
        Instant betaEnd = betaStart.plus(45, ChronoUnit.DAYS);

        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", betaStart, betaEnd);
        sub.setBetaStartedAt(betaStart);
        sub.setBetaEndsAt(betaEnd);
        subscriptionRepository.save(sub);

        int first = lifecycleService.sendBetaExpiryWarnings();
        assertThat(first).isEqualTo(1);

        int second = lifecycleService.sendBetaExpiryWarnings();
        assertThat(second).isEqualTo(0);

        int third = lifecycleService.sendBetaExpiryWarnings();
        assertThat(third).isEqualTo(0);

        assertThat(noticeRepository.findAll()).hasSize(1);
    }

    // =========================================================================
    // 10. Payment Failure Enters Grace Period
    // =========================================================================

    @Test
    @DisplayName("Payment failure moves subscription into PAST_DUE state with pastDueSince timestamp")
    void paymentFailureEntersGracePeriod() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRO", "PAST_DUE", "USD", "US", "MONTHLY", now.minus(10, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS));
        sub.setPastDueSince(now);
        subscriptionRepository.save(sub);

        Subscription current = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo("PAST_DUE");
        assertThat(current.getPastDueSince()).isNotNull();
    }

    // =========================================================================
    // 11. Payment Failure Within Grace Period Does Not Prematurely Downgrade
    // =========================================================================

    @Test
    @DisplayName("During the 7-day grace period, the organization remains on its plan without premature downgrade")
    void paymentFailureWithinGracePeriodDoesNotPrematurelyDowngrade() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRO", "PAST_DUE", "USD", "US", "MONTHLY", now.minus(10, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS));
        sub.setPastDueSince(now.minus(4, ChronoUnit.DAYS)); // 4 days into grace (< 7 days)
        subscriptionRepository.save(sub);

        int downgraded = lifecycleService.processDunningAndGracePeriods();
        assertThat(downgraded).isEqualTo(0);

        Subscription current = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(current.getPlan()).isEqualTo("PRO");
        assertThat(current.getStatus()).isEqualTo("PAST_DUE");
    }

    // =========================================================================
    // 12. After 7 Days Correct Enforcement Occurs
    // =========================================================================

    @Test
    @DisplayName("After 7 days of grace period, subscription is downgraded to FREE and limits enforced")
    void after7DaysCorrectEnforcementOccurs() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRO", "PAST_DUE", "USD", "US", "MONTHLY", now.minus(20, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS));
        sub.setPastDueSince(now.minus(8, ChronoUnit.DAYS)); // 8 days into grace (> 7 days)
        subscriptionRepository.save(sub);

        int downgraded = lifecycleService.processDunningAndGracePeriods();
        assertThat(downgraded).isEqualTo(1);

        Subscription current = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(current.getPlan()).isEqualTo("FREE");

        List<BillingAuditLog> auditLogs = auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId());
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("DUNNING_GRACE_EXPIRED_DOWNGRADE");
    }

    // =========================================================================
    // 13. Repeated Dunning Execution is Idempotent
    // =========================================================================

    @Test
    @DisplayName("Repeated dunning execution after grace expiry is idempotent")
    void repeatedDunningExecutionIsIdempotent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRO", "PAST_DUE", "USD", "US", "MONTHLY", now.minus(20, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS));
        sub.setPastDueSince(now.minus(9, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        lifecycleService.processDunningAndGracePeriods();
        int repeated = lifecycleService.processDunningAndGracePeriods();
        assertThat(repeated).isEqualTo(0);

        assertThat(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId())).hasSize(1);
    }

    // =========================================================================
    // 14. Founding Offer is Applied Exactly Once
    // =========================================================================

    @Test
    @DisplayName("Founding offer applies successfully on first call and rejects duplicate application")
    void foundingOfferIsAppliedExactlyOnce() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", now.minus(5, ChronoUnit.DAYS), now.plus(40, ChronoUnit.DAYS));
        sub.setFoundingOfferEligibleUntil(now.plus(40, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        BillingDiscount discount = lifecycleService.applyFoundingOffer(org1.getId());
        assertThat(discount.getCode()).isEqualTo("FOUNDING_30");
        assertThat(discount.getDiscountPct()).isEqualTo(30);

        assertThatThrownBy(() -> lifecycleService.applyFoundingOffer(org1.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(discountRepository.findByOrganizationId(org1.getId())).hasSize(1);
    }

    // =========================================================================
    // 15. Founding Offer Remains Valid for 12 Months
    // =========================================================================

    @Test
    @DisplayName("Founding offer sets valid period of exactly 12 months (365 days)")
    void foundingOfferRemainsValidFor12Months() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", now.minus(5, ChronoUnit.DAYS), now.plus(40, ChronoUnit.DAYS));
        sub.setFoundingOfferEligibleUntil(now.plus(40, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        BillingDiscount discount = lifecycleService.applyFoundingOffer(org1.getId());
        long days = ChronoUnit.DAYS.between(discount.getStartsAt(), discount.getEndsAt());
        assertThat(days).isEqualTo(365);
    }

    // =========================================================================
    // 16. Concurrent Scheduled-Job Execution
    // =========================================================================

    @Test
    @DisplayName("Concurrent scheduled-job sweeps execute safely without duplicate audit rows or errors")
    void concurrentScheduledJobExecution() throws Exception {
        Instant past = Instant.now().minus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Subscription sub1 = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", past, past.plus(45, ChronoUnit.DAYS));
        sub1.setBetaEndsAt(past.plus(45, ChronoUnit.DAYS));
        subscriptionRepository.save(sub1);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    lifecycleScheduler.runLifecycleSweep();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        Subscription subAfter = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(subAfter.getPlan()).isEqualTo("FREE");

        List<BillingAuditLog> logs = auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId());
        assertThat(logs).hasSize(1);
    }

    // =========================================================================
    // 17. Multi-Tenant Isolation
    // =========================================================================

    @Test
    @DisplayName("Lifecycle jobs strictly isolate tenants without cross-organization leakage")
    void multiTenantIsolation() {
        Instant past = Instant.now().minus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Org 1 is expired beta
        Subscription sub1 = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", past, past.plus(45, ChronoUnit.DAYS));
        sub1.setBetaEndsAt(past.plus(45, ChronoUnit.DAYS));
        subscriptionRepository.save(sub1);

        // Org 2 is active PRO
        Subscription sub2 = Subscription.create(org2.getId(), "PRO", "ACTIVE", "USD", "US", "MONTHLY", now, now.plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub2);

        // Org 1 has 5 workflows
        for (int i = 0; i < 5; i++) {
            Workflow wf = Workflow.create(org1.getId(), userId, "Org1-WF-" + i, null, objectMapper.createObjectNode());
            wf.markPublished(1);
            workflowRepository.save(wf);
        }

        // Org 2 has 5 workflows
        for (int i = 0; i < 5; i++) {
            Workflow wf = Workflow.create(org2.getId(), userId, "Org2-WF-" + i, null, objectMapper.createObjectNode());
            wf.markPublished(1);
            workflowRepository.save(wf);
        }

        lifecycleScheduler.runLifecycleSweep();

        // Org 1 downgraded and capped to 3
        Subscription updatedSub1 = subscriptionRepository.findByWorkspaceId(org1.getId()).orElseThrow();
        assertThat(updatedSub1.getPlan()).isEqualTo("FREE");
        assertThat(workflowRepository.findPublishedByOrganizationIdOrderByCreatedAtAscIdAsc(org1.getId())).hasSize(3);

        // Org 2 remains PRO with all 5 workflows active
        Subscription updatedSub2 = subscriptionRepository.findByWorkspaceId(org2.getId()).orElseThrow();
        assertThat(updatedSub2.getPlan()).isEqualTo("PRO");
        assertThat(updatedSub2.getStatus()).isEqualTo("ACTIVE");
        assertThat(workflowRepository.findPublishedByOrganizationIdOrderByCreatedAtAscIdAsc(org2.getId())).hasSize(5);
    }

    // =========================================================================
    // 18. Founding Offer Invokes Billing Provider & Provider Failure Rolls Back
    // =========================================================================

    @Test
    @DisplayName("Founding offer requests 30% discount and 12-month duration from BillingProvider abstraction")
    void foundingOfferRequestsProviderDiscount() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", now.minus(5, ChronoUnit.DAYS), now.plus(40, ChronoUnit.DAYS));
        sub.setFoundingOfferEligibleUntil(now.plus(40, ChronoUnit.DAYS));
        sub.setProviderCustomerId("cus_test_123");
        sub.setProviderSubscriptionId("sub_test_123");
        subscriptionRepository.save(sub);

        BillingDiscount discount = lifecycleService.applyFoundingOffer(org1.getId());
        assertThat(discount.getProviderCouponId()).isNotBlank();
        assertThat(discount.getDiscountPct()).isEqualTo(30);

        List<BillingAuditLog> logs = auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("FOUNDING_OFFER_APPLIED");
        assertThat(logs.get(0).getReason()).contains("30%");
    }

    @Test
    @DisplayName("Provider failure during discount application does not leave false local discount record")
    void providerFailureRollsBackLocalDiscountRecord() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(org1.getId(), "PRIVATE_BETA", "ACTIVE", "USD", "US", "MONTHLY", now.minus(5, ChronoUnit.DAYS), now.plus(40, ChronoUnit.DAYS));
        sub.setFoundingOfferEligibleUntil(now.plus(40, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        com.flowops.billing.provider.BillingProvider failingProvider = org.mockito.Mockito.mock(com.flowops.billing.provider.BillingProvider.class);
        org.mockito.Mockito.when(failingProvider.applyDiscount(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Provider connection refused"));

        SubscriptionLifecycleService serviceWithFailingProvider = new SubscriptionLifecycleService(
                subscriptionRepository,
                workflowRepository,
                executionRepository,
                discountRepository,
                auditLogRepository,
                billingNoticeService,
                null,
                failingProvider,
                objectMapper);

        assertThatThrownBy(() -> serviceWithFailingProvider.applyFoundingOffer(org1.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Provider connection refused");

        // Assert local database has 0 discount records and 0 audit logs
        assertThat(discountRepository.findByOrganizationId(org1.getId())).isEmpty();
        assertThat(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(org1.getId())).isEmpty();
    }

    // =========================================================================
    // 19. Distributed Redis Lock Robustness
    // =========================================================================

    @Test
    @DisplayName("Distributed Redis lock: first instance acquires, second cannot, owner token releases, wrong token cannot release")
    void distributedRedisLockSemantics() {
        org.springframework.data.redis.core.StringRedisTemplate mockRedis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);

        org.mockito.Mockito.when(mockRedis.opsForValue()).thenReturn(valueOps);

        BillingLifecycleScheduler schedulerWithMockRedis =
                new BillingLifecycleScheduler(lifecycleService, mockRedis);

        String token1 = "token-1";
        String token2 = "token-2";

        // 1. First instance acquires lock successfully
        org.mockito.Mockito.when(valueOps.setIfAbsent(
                org.mockito.ArgumentMatchers.eq(BillingLifecycleScheduler.LOCK_KEY),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(BillingLifecycleScheduler.LOCK_TTL)))
                .thenReturn(true);

        // 2. Second instance cannot acquire (returns false)
        org.mockito.Mockito.when(valueOps.setIfAbsent(
                org.mockito.ArgumentMatchers.eq(BillingLifecycleScheduler.LOCK_KEY),
                org.mockito.ArgumentMatchers.eq(token2),
                org.mockito.ArgumentMatchers.eq(BillingLifecycleScheduler.LOCK_TTL)))
                .thenReturn(false);

        // 3. Lua script release: returns 1 (success) when token matches, 0 when token does not match
        org.mockito.Mockito.when(mockRedis.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(java.util.Collections.singletonList(BillingLifecycleScheduler.LOCK_KEY)),
                org.mockito.ArgumentMatchers.eq(token1)))
                .thenReturn(1L);

        org.mockito.Mockito.when(mockRedis.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(java.util.Collections.singletonList(BillingLifecycleScheduler.LOCK_KEY)),
                org.mockito.ArgumentMatchers.eq(token2)))
                .thenReturn(0L);

        // Owner releases successfully
        assertThat(schedulerWithMockRedis.releaseLock(token1)).isTrue();

        // Non-owner (wrong token / expired token) cannot release
        assertThat(schedulerWithMockRedis.releaseLock(token2)).isFalse();
    }

    @Test
    @DisplayName("When Redis fails, scheduler skips sweep rather than running concurrently without lock")
    void whenRedisFailsSchedulerSkipsSweep() {
        org.springframework.data.redis.core.StringRedisTemplate failingRedis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);

        org.mockito.Mockito.when(failingRedis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(valueOps.setIfAbsent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Cannot connect to Redis"));

        SubscriptionLifecycleService mockLifecycle = org.mockito.Mockito.mock(SubscriptionLifecycleService.class);
        BillingLifecycleScheduler scheduler = new BillingLifecycleScheduler(mockLifecycle, failingRedis);

        // Run sweep
        scheduler.runLifecycleSweep();

        // Verify lifecycle sweeps are SKIPPED (0 interactions)
        org.mockito.Mockito.verifyNoInteractions(mockLifecycle);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private void setCreatedAt(Object entity, Instant instant) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, instant);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
