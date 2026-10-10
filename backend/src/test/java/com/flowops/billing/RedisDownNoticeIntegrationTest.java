package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.service.BillingNoticeMailer;
import com.flowops.billing.service.BillingNoticeService;
import com.flowops.billing.service.BillingPeriodService;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.domain.BillingNotice;
import com.flowops.domain.Notification;
import com.flowops.domain.Organization;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.domain.UserAccount;
import com.flowops.notification.NotificationService;
import com.flowops.repository.BillingNoticeRepository;
import com.flowops.repository.InvitationRepository;
import com.flowops.repository.NotificationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RedisDownNoticeIntegrationTest {

    @Autowired
    private BillingNoticeRepository noticeRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private OrganizationMemberRepository memberRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private UsageMeterService usageMeterService;

    @Autowired
    private BillingPeriodService billingPeriodService;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization org;
    private UserAccount ownerUser;
    private Instant periodStart;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        noticeRepository.deleteAll();
        notificationRepository.deleteAll();

        org = organizationRepository.save(
                Organization.create("Redis Down Org " + UUID.randomUUID(), "rdown-" + UUID.randomUUID().toString().substring(0, 8)));
        ownerUser = userRepository.save(
                UserAccount.create("owner-" + UUID.randomUUID() + "@test.local", "pw", "Notice Owner"));

        memberRepository.save(OrganizationMember.create(ownerUser.getId(), org.getId(), Role.OWNER));

        com.flowops.billing.BillingPeriod current = com.flowops.billing.BillingPeriod.currentMonth();
        periodStart = current.start();
        Instant periodEnd = current.end();
        Subscription sub = Subscription.create(
                org.getId(),
                "FREE",
                "active",
                "USD",
                "US",
                "monthly",
                periodStart,
                periodEnd);
        subscriptionRepository.save(sub);

        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (org != null) {
            noticeRepository.deleteAll();
            notificationRepository.deleteAll();
            memberRepository.deleteAll();
            subscriptionRepository.deleteAll();
            userRepository.deleteById(ownerUser.getId());
            organizationRepository.deleteById(org.getId());
        }
    }

    @Test
    @DisplayName("Redis down notice test: failing Redis triggers fallback to DB and delivers exactly one notice")
    void redisDownNoticeFallbackToDbDeliversNotice() {
        // Set up a failing StringRedisTemplate that throws RedisConnectionFailureException
        StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(failingRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis at localhost:6379"));

        BillingNoticeMailer mockMailer = mock(BillingNoticeMailer.class);
        BillingNoticeService billingNoticeService = new BillingNoticeService(
                noticeRepository, notificationService, memberRepository, userRepository, mockMailer);

        EntitlementService entitlementService = new EntitlementService(
                subscriptionRepository,
                usageMeterService,
                billingPeriodService,
                workflowRepository,
                invitationRepository,
                memberRepository,
                billingNoticeService,
                failingRedis,
                objectMapper,
                meterRegistry,
                "enforce");

        // Seed usage up to 999 (limit is 1000 for FREE)
        for (int i = 0; i < 999; i++) {
            usageMeterService.reserveExecution(org.getId(), periodStart, periodStart.plus(30, ChronoUnit.DAYS), 10000);
        }
        noticeRepository.deleteAll();
        notificationRepository.deleteAll();

        // 1000th reservation crosses 100% threshold with Redis down
        var ticket = entitlementService.reserveExecution(org.getId());
        assertThat(ticket.reserved()).isTrue();

        // Verify fail-open metric recorded the redis error
        assertThat(meterRegistry.counter("entitlements.fail_open", "reason", "redis_error").count()).isGreaterThanOrEqualTo(1.0);

        // Verify exactly one DB notice row was recorded via PostgreSQL table
        List<BillingNotice> notices = noticeRepository.findAll();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getThreshold()).isEqualTo(100);
        assertThat(notices.get(0).getKind()).isEqualTo("executions");

        // Verify exactly one in-app notification was published
        List<Notification> notifications = notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getTitle()).contains("Monthly Execution Limit Reached");
    }
}
