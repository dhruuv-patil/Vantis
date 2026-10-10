package com.flowops.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import com.flowops.billing.provider.model.WebhookProcessResult;
import com.flowops.billing.provider.razorpay.RazorpayProperties;
import com.flowops.billing.provider.razorpay.RazorpaySignatureVerifier;
import com.flowops.billing.provider.stripe.StripeProperties;
import com.flowops.billing.provider.stripe.StripeSignatureVerifier;
import com.flowops.domain.BillingEvent;
import com.flowops.domain.Organization;
import com.flowops.domain.Subscription;
import com.flowops.repository.BillingEventRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.SubscriptionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "flowops.billing.enabled=true",
        "flowops.billing.provider=noop"
})
class BillingWebhookIntegrationTest {

    @Autowired
    private BillingWebhookService webhookService;

    @Autowired
    private BillingEventClaimer billingEventClaimer;

    @Autowired
    private BillingEventRepository billingEventRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private StripeProperties stripeProperties;

    @Autowired
    private RazorpayProperties razorpayProperties;

    private Organization testOrg;
    private Subscription testSub;
    private static final String STRIPE_SECRET = "whsec_test_integration_stripe_123";
    private static final String RZP_SECRET = "rzp_sec_integration_test_123";

    @BeforeEach
    void setUp() {
        stripeProperties.setWebhookSecret(STRIPE_SECRET);
        razorpayProperties.setWebhookSecret(RZP_SECRET);

        testOrg = organizationRepository.save(Organization.create(
                "Billing Webhook Org", "billing-webhook-org-" + UUID.randomUUID().toString().substring(0, 8)));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        testSub = Subscription.create(
                testOrg.getId(),
                "PRO",
                "ACTIVE",
                "USD",
                "US",
                "MONTHLY",
                now.minus(10, ChronoUnit.DAYS),
                now.plus(20, ChronoUnit.DAYS)
        );
        String subId = "sub_test_stripe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String custId = "cus_test_stripe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        testSub.setProviderSubscriptionId(subId);
        testSub.setProviderCustomerId(custId);
        testSub = subscriptionRepository.save(testSub);
    }

    @Test
    @DisplayName("Valid Stripe webhook signature processes successfully and updates subscription")
    void validStripeWebhookProcessed() {
        String eventId = "evt_stripe_valid_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.updated\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"active\",\"plan\":{\"id\":\"price_pro_usd_monthly\"}}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        Map<String, String> headers = Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig);

        WebhookProcessResult result = webhookService.processWebhook("stripe", rawPayload, headers);

        assertThat(result.status()).isEqualTo("PROCESSED");

        BillingEvent event = billingEventRepository.findByProviderAndProviderEventId("STRIPE", eventId)
                .orElseThrow();
        assertThat(event.getStatus()).isEqualTo("PROCESSED");
        assertThat(event.getProcessedAt()).isNotNull();

        Subscription updatedSub = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(updatedSub.getStatus()).isEqualTo("ACTIVE");
        assertThat(updatedSub.getLastWebhookEventAt()).isNotNull();
    }

    @Test
    @DisplayName("Invalid Stripe signature is rejected without modifying subscription or storing event")
    void invalidStripeSignatureRejected() {
        String eventId = "evt_stripe_invalid_" + UUID.randomUUID().toString().substring(0, 8);
        String rawPayload = String.format("{\"id\":\"%s\",\"type\":\"invoice.paid\"}", eventId);
        Map<String, String> headers = Map.of("Stripe-Signature", "t=1620000000,v1=bad_signature");

        assertThatThrownBy(() -> webhookService.processWebhook("stripe", rawPayload, headers))
                .isInstanceOf(BillingSignatureVerificationException.class)
                .hasMessageContaining("Stripe webhook signature verification failed");

        assertThat(billingEventRepository.findByProviderAndProviderEventId("STRIPE", eventId)).isEmpty();
    }

    @Test
    @DisplayName("Valid Razorpay webhook signature processes successfully")
    void validRazorpayWebhookProcessed() {
        String rzpSubId = "sub_rzp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        testSub.setProviderSubscriptionId(rzpSubId);
        testSub.setCurrency("INR");
        subscriptionRepository.save(testSub);

        String eventId = "evt_rzp_valid_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"entity\":\"event\",\"event\":\"subscription.charged\",\"created_at\":%d," +
                "\"payload\":{\"subscription\":{\"entity\":{\"id\":\"%s\",\"status\":\"active\"}}}}",
                eventId, epoch, rzpSubId);

        String sig = RazorpaySignatureVerifier.computeHmacSha256(rawPayload, RZP_SECRET);
        Map<String, String> headers = Map.of("X-Razorpay-Signature", sig);

        WebhookProcessResult result = webhookService.processWebhook("razorpay", rawPayload, headers);

        assertThat(result.status()).isEqualTo("PROCESSED");

        BillingEvent event = billingEventRepository.findByProviderAndProviderEventId("RAZORPAY", eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("Invalid Razorpay signature is rejected")
    void invalidRazorpaySignatureRejected() {
        String eventId = "evt_rzp_invalid_" + UUID.randomUUID().toString().substring(0, 8);
        String rawPayload = String.format("{\"id\":\"%s\",\"event\":\"payment.failed\"}", eventId);
        Map<String, String> headers = Map.of("X-Razorpay-Signature", "invalid_sig");

        assertThatThrownBy(() -> webhookService.processWebhook("razorpay", rawPayload, headers))
                .isInstanceOf(BillingSignatureVerificationException.class)
                .hasMessageContaining("Razorpay webhook signature verification failed");

        assertThat(billingEventRepository.findByProviderAndProviderEventId("RAZORPAY", eventId)).isEmpty();
    }

    @Test
    @DisplayName("Duplicate webhook delivered twice results in exactly ONE state application")
    void duplicateWebhookDeliveryIdempotent() {
        String eventId = "evt_stripe_dup_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.updated\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"active\"}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        Map<String, String> headers = Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig);

        // 1st delivery
        WebhookProcessResult firstResult = webhookService.processWebhook("stripe", rawPayload, headers);
        assertThat(firstResult.status()).isEqualTo("PROCESSED");

        // 2nd delivery of the EXACT same payload
        WebhookProcessResult secondResult = webhookService.processWebhook("stripe", rawPayload, headers);
        assertThat(secondResult.status()).isEqualTo("IGNORED_DUPLICATE");

        // Verify exactly one row in billing_events
        long eventCount = billingEventRepository.findAll().stream()
                .filter(e -> eventId.equals(e.getProviderEventId()))
                .count();
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent duplicate webhook delivery is atomic: exactly one processes, others ignored")
    void concurrentDuplicateWebhookDelivery() throws Exception {
        String eventId = "evt_stripe_race_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.updated\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"active\"}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        Map<String, String> headers = Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig);

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<WebhookProcessResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return webhookService.processWebhook("stripe", rawPayload, headers);
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int processedCount = 0;
        int ignoredDuplicateCount = 0;

        for (Future<WebhookProcessResult> f : futures) {
            WebhookProcessResult res = f.get();
            if ("PROCESSED".equals(res.status())) {
                processedCount++;
            } else if ("IGNORED_DUPLICATE".equals(res.status())) {
                ignoredDuplicateCount++;
            }
        }

        // Exactly one should process, the remaining (threads - 1) should be ignored duplicates
        assertThat(processedCount).isEqualTo(1);
        assertThat(ignoredDuplicateCount).isEqualTo(threads - 1);

        long dbCount = billingEventRepository.findAll().stream()
                .filter(e -> eventId.equals(e.getProviderEventId()))
                .count();
        assertThat(dbCount).isEqualTo(1);

        Subscription currentSub = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(currentSub.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Direct database claim concurrency: exactly one thread claims row, others receive already-claimed cleanly without UnexpectedRollbackException")
    void directDatabaseConcurrentClaimAtomic() throws Exception {
        String eventId = "evt_db_race_" + UUID.randomUUID().toString().substring(0, 8);
        Instant eventTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String rawPayload = "{\"id\":\"" + eventId + "\",\"type\":\"customer.subscription.updated\"}";

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Optional<BillingEvent>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return billingEventClaimer.tryClaim(
                        "STRIPE",
                        eventId,
                        "customer.subscription.updated",
                        eventTime,
                        rawPayload,
                        testOrg.getId()
                );
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int successfulClaims = 0;
        int duplicateClaims = 0;

        for (Future<Optional<BillingEvent>> f : futures) {
            Optional<BillingEvent> claimResult = f.get();
            if (claimResult.isPresent()) {
                successfulClaims++;
            } else {
                duplicateClaims++;
            }
        }

        // Exactly one claim succeeds at database level
        assertThat(successfulClaims).isEqualTo(1);
        assertThat(duplicateClaims).isEqualTo(threads - 1);

        // Exactly one row persisted in billing_events table
        long dbCount = billingEventRepository.findAll().stream()
                .filter(e -> eventId.equals(e.getProviderEventId()))
                .count();
        assertThat(dbCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Out-of-order events: older event received after newer event never rolls subscription state backwards")
    void outOfOrderEventsProtected() {
        Instant t200 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant t100 = t200.minus(100, ChronoUnit.SECONDS);

        // Deliver Event 1 (newer event, created at t200, status CANCELED)
        String event1Id = "evt_stripe_t200_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch200 = t200.getEpochSecond();
        String payload200 = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.deleted\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"canceled\"}}}",
                event1Id, epoch200, testSub.getProviderSubscriptionId());

        String sig200 = StripeSignatureVerifier.computeHmacSha256(epoch200 + "." + payload200, STRIPE_SECRET);
        WebhookProcessResult res200 = webhookService.processWebhook("stripe", payload200, Map.of("Stripe-Signature", "t=" + epoch200 + ",v1=" + sig200));
        assertThat(res200.status()).isEqualTo("PROCESSED");

        Subscription subAfterEvent1 = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(subAfterEvent1.getStatus()).isEqualTo("CANCELED");
        assertThat(subAfterEvent1.getLastWebhookEventAt()).isEqualTo(Instant.ofEpochSecond(epoch200));

        // Now deliver Event 2 (older event, created at t100, status ACTIVE)
        String event2Id = "evt_stripe_t100_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch100 = t100.getEpochSecond();
        String payload100 = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.updated\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"active\"}}}",
                event2Id, epoch100, testSub.getProviderSubscriptionId());

        String sig100 = StripeSignatureVerifier.computeHmacSha256(epoch100 + "." + payload100, STRIPE_SECRET);
        WebhookProcessResult res100 = webhookService.processWebhook("stripe", payload100, Map.of("Stripe-Signature", "t=" + epoch100 + ",v1=" + sig100));

        // Event 2 is recognized as OUT_OF_ORDER and ignored!
        assertThat(res100.status()).isEqualTo("IGNORED_OUT_OF_ORDER");

        // Subscription state MUST NOT be rolled back to ACTIVE; it remains CANCELED!
        Subscription subAfterEvent2 = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(subAfterEvent2.getStatus()).isEqualTo("CANCELED");

        BillingEvent event2Record = billingEventRepository.findByProviderAndProviderEventId("STRIPE", event2Id).orElseThrow();
        assertThat(event2Record.getStatus()).isEqualTo("IGNORED");
        assertThat(event2Record.getErrorMessage()).contains("Out-of-order event");
    }

    @Test
    @DisplayName("Payment failed event transitions subscription to PAST_DUE and starts dunning grace period")
    void paymentFailedTransitionsToPastDueAndDunningGrace() {
        String eventId = "evt_stripe_failed_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"invoice.payment_failed\",\"created\":%d," +
                "\"data\":{\"object\":{\"subscription\":\"%s\",\"status\":\"open\",\"paid\":false}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        WebhookProcessResult result = webhookService.processWebhook("stripe", rawPayload, Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig));

        assertThat(result.status()).isEqualTo("PROCESSED");

        Subscription pastDueSub = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(pastDueSub.getStatus()).isEqualTo("PAST_DUE");
        // Dunning grace period started
        assertThat(pastDueSub.getPastDueSince()).isNotNull();
        assertThat(pastDueSub.getPastDueSince()).isEqualTo(Instant.ofEpochSecond(epoch));
    }

    @Test
    @DisplayName("Payment recovery via invoice.paid transitions subscription back to ACTIVE and clears pastDueSince")
    void paymentRecoveryClearsPastDue() {
        // Set subscription to PAST_DUE first
        testSub.setStatus("PAST_DUE");
        testSub.setPastDueSince(Instant.now().minus(3, ChronoUnit.DAYS));
        testSub = subscriptionRepository.save(testSub);

        String eventId = "evt_stripe_recovery_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"invoice.paid\",\"created\":%d," +
                "\"data\":{\"object\":{\"subscription\":\"%s\",\"paid\":true}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        WebhookProcessResult result = webhookService.processWebhook("stripe", rawPayload, Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig));

        assertThat(result.status()).isEqualTo("PROCESSED");

        Subscription recoveredSub = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(recoveredSub.getStatus()).isEqualTo("ACTIVE");
        assertThat(recoveredSub.getPastDueSince()).isNull();
    }

    @Test
    @DisplayName("Subscription cancellation event transitions subscription to CANCELED")
    void subscriptionCanceledTransitionsToCanceled() {
        String eventId = "evt_stripe_cancel_" + UUID.randomUUID().toString().substring(0, 8);
        long epoch = Instant.now().getEpochSecond();
        String rawPayload = String.format(
                "{\"id\":\"%s\",\"type\":\"customer.subscription.deleted\",\"created\":%d," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"canceled\"}}}",
                eventId, epoch, testSub.getProviderSubscriptionId());

        String sig = StripeSignatureVerifier.computeHmacSha256(epoch + "." + rawPayload, STRIPE_SECRET);
        WebhookProcessResult result = webhookService.processWebhook("stripe", rawPayload, Map.of("Stripe-Signature", "t=" + epoch + ",v1=" + sig));

        assertThat(result.status()).isEqualTo("PROCESSED");

        Subscription cancelledSub = subscriptionRepository.findById(testSub.getId()).orElseThrow();
        assertThat(cancelledSub.getStatus()).isEqualTo("CANCELED");
        assertThat(cancelledSub.isCancelAtPeriodEnd()).isFalse();
    }
}
