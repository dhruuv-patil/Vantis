package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowops.billing.model.ReservationResult;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.domain.Organization;
import com.flowops.repository.OrganizationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UsageMeterServiceConcurrencyTest {

    @Autowired
    private UsageMeterService usageMeterService;

    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID orgId;
    private Instant periodStart;
    private Instant periodEnd;

    @BeforeEach
    void setUp() {
        Organization org = Organization.create("Meter Org " + UUID.randomUUID(), "meter-" + UUID.randomUUID().toString().substring(0, 8));
        org = organizationRepository.save(org);
        orgId = org.getId();

        periodStart = Instant.now().minusSeconds(3600).with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
        periodEnd = Instant.now().plusSeconds(86400 * 30);
    }

    @AfterEach
    void tearDown() {
        if (orgId != null) {
            organizationRepository.deleteById(orgId);
        }
    }

    @Test
    @DisplayName("High concurrency atomic reservation: exactly limit succeed, excess are blocked without over-counting")
    void concurrentExecutionReservationsEnforceLimitAccurately() throws Exception {
        int threads = 20;
        int limit = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ReservationResult result = usageMeterService.reserveExecution(orgId, periodStart, periodEnd, limit);
                    if (result.allowed()) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all threads concurrently

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(exceptions).isEmpty();
        assertThat(successCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(threads - limit);

        // Authoritative count in DB must equal limit
        long recorded = usageMeterService.getCurrentUsage(orgId, periodStart, "executions");
        assertThat(recorded).isEqualTo(limit);
    }

    @Test
    @DisplayName("Safe refund: decrements count without dropping below zero")
    void refundExecutionSafeDecrement() {
        // Reserve 2
        usageMeterService.reserveExecution(orgId, periodStart, periodEnd, 10);
        usageMeterService.reserveExecution(orgId, periodStart, periodEnd, 10);
        assertThat(usageMeterService.getCurrentUsage(orgId, periodStart, "executions")).isEqualTo(2);

        // Refund 1
        usageMeterService.refundExecution(orgId, periodStart);
        assertThat(usageMeterService.getCurrentUsage(orgId, periodStart, "executions")).isEqualTo(1);

        // Refund 2 more (should floor at 0)
        usageMeterService.refundExecution(orgId, periodStart);
        usageMeterService.refundExecution(orgId, periodStart);
        assertThat(usageMeterService.getCurrentUsage(orgId, periodStart, "executions")).isEqualTo(0);
    }
}
