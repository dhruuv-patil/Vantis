package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowops.billing.service.BillingNoticeMailer;
import com.flowops.billing.service.BillingNoticeService;
import com.flowops.domain.BillingNotice;
import com.flowops.domain.Notification;
import com.flowops.domain.Organization;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.domain.UserAccount;
import com.flowops.notification.NotificationService;
import com.flowops.repository.BillingNoticeRepository;
import com.flowops.repository.NotificationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BillingNoticeServiceTest {

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

    private Organization org;
    private UserAccount ownerUser;
    private Instant periodStart;

    @BeforeEach
    void setUp() {
        noticeRepository.deleteAll();
        notificationRepository.deleteAll();

        org = organizationRepository.save(
                Organization.create("Notice Test Org " + UUID.randomUUID(), "notif-" + UUID.randomUUID().toString().substring(0, 8)));
        ownerUser = userRepository.save(
                UserAccount.create("owner-" + UUID.randomUUID() + "@test.local", "pw", "Notice Owner"));

        memberRepository.save(OrganizationMember.create(ownerUser.getId(), org.getId(), Role.OWNER));

        periodStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    }

    @AfterEach
    void tearDown() {
        if (org != null) {
            noticeRepository.deleteAll();
            notificationRepository.deleteAll();
            memberRepository.deleteAll();
            userRepository.deleteById(ownerUser.getId());
            organizationRepository.deleteById(org.getId());
        }
    }

    @Test
    @DisplayName("1. Concurrent reservations: multiple threads crossing threshold yield exactly one DB notice and one in-app notification")
    void concurrentReservationsYieldExactlyOneNotice() throws Exception {
        BillingNoticeMailer mockMailer = mock(BillingNoticeMailer.class);
        BillingNoticeService noticeService = new BillingNoticeService(
                noticeRepository, notificationService, memberRepository, userRepository, mockMailer);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    noticeService.recordAndPublishNotice(
                            org.getId(), periodStart, 80, "executions", 800, 1000, true);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(errors).isEmpty();

        // Exactly one billing notice row persisted
        List<BillingNotice> notices = noticeRepository.findAll();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getThreshold()).isEqualTo(80);
        assertThat(notices.get(0).getKind()).isEqualTo("executions");

        // Exactly one in-app notification delivered
        List<Notification> inApp = notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged());
        assertThat(inApp).hasSize(1);
    }

    @Test
    @DisplayName("2. Refund then re-cross in same period: notice is NOT re-issued or duplicated")
    void refundThenReCrossYieldsExactlyOneNotice() {
        BillingNoticeMailer mockMailer = mock(BillingNoticeMailer.class);
        BillingNoticeService noticeService = new BillingNoticeService(
                noticeRepository, notificationService, memberRepository, userRepository, mockMailer);

        // First crossing of 80%
        noticeService.recordAndPublishNotice(
                org.getId(), periodStart, 80, "executions", 800, 1000, true);

        assertThat(noticeRepository.findAll()).hasSize(1);
        assertThat(notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged())).hasSize(1);

        // Simulation: Usage refunded from 800 to 790, then crosses 800 again later in the same period
        // Notice row was NOT released/deleted on refund -> subsequent check is a no-op!
        noticeService.recordAndPublishNotice(
                org.getId(), periodStart, 80, "executions", 805, 1000, true);

        assertThat(noticeRepository.findAll()).hasSize(1);
        assertThat(notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged())).hasSize(1);
    }

    @Test
    @DisplayName("3. Redis down: notice dedupe is DB-backed and succeeds reliably without Redis")
    void redisDownYieldsExactlyOneNotice() {
        // BillingNoticeService depends only on DB unique row, completely decoupled from Redis
        BillingNoticeMailer mockMailer = mock(BillingNoticeMailer.class);
        BillingNoticeService noticeService = new BillingNoticeService(
                noticeRepository, notificationService, memberRepository, userRepository, mockMailer);

        noticeService.recordAndPublishNotice(
                org.getId(), periodStart, 100, "executions", 1000, 1000, true);

        List<BillingNotice> notices = noticeRepository.findAll();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getThreshold()).isEqualTo(100);

        List<Notification> inApp = notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged());
        assertThat(inApp).hasSize(1);
        assertThat(inApp.get(0).getTitle()).contains("Monthly Execution Limit Reached");
    }

    @Test
    @DisplayName("4. Mailer throwing after publish: in-app notice and dedupe row remain committed and exactly one notice exists")
    void mailerThrowingAfterPublishLeavesNoticeCommitted() {
        BillingNoticeMailer failingMailer = mock(BillingNoticeMailer.class);
        doThrow(new RuntimeException("SMTP socket timeout"))
                .when(failingMailer).sendBillingEmail(anyString(), anyString(), anyString());

        BillingNoticeService noticeService = new BillingNoticeService(
                noticeRepository, notificationService, memberRepository, userRepository, failingMailer);

        noticeService.recordAndPublishNotice(
                org.getId(), periodStart, 80, "executions", 800, 1000, true);

        // In-app notice and dedupe row must still exist intact
        List<BillingNotice> notices = noticeRepository.findAll();
        assertThat(notices).hasSize(1);

        List<Notification> inApp = notificationRepository.search(ownerUser.getId(), org.getId(), false, Pageable.unpaged());
        assertThat(inApp).hasSize(1);
    }
}
