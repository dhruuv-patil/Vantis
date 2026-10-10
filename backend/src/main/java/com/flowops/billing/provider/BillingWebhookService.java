package com.flowops.billing.provider;

import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import com.flowops.billing.provider.exception.BillingValidationException;
import com.flowops.billing.provider.model.WebhookEventResult;
import com.flowops.billing.provider.model.WebhookProcessResult;
import com.flowops.billing.provider.razorpay.RazorpayBillingProvider;
import com.flowops.billing.provider.stripe.StripeBillingProvider;
import com.flowops.billing.service.EntitlementService;
import com.flowops.domain.BillingEvent;
import com.flowops.domain.Subscription;
import com.flowops.repository.BillingEventRepository;
import com.flowops.repository.SubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative billing webhook processing and lifecycle engine.
 * - Enforces raw body cryptographic signature verification
 * - Ensures atomic idempotency via billing_events (provider, provider_event_id) unique constraint
 * - Protects against out-of-order events using provider event timestamps
 * - Synchronizes subscription state exclusively from verified webhooks
 * - Emits structured audit logs and metrics without leaking secrets
 */
@Service
public class BillingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookService.class);

    private final org.springframework.beans.factory.ObjectProvider<StripeBillingProvider> stripeProvider;
    private final org.springframework.beans.factory.ObjectProvider<RazorpayBillingProvider> razorpayProvider;
    private final BillingEventRepository billingEventRepository;
    private final BillingEventClaimer billingEventClaimer;
    private final SubscriptionRepository subscriptionRepository;
    private final EntitlementService entitlementService;
    private final MeterRegistry meterRegistry;
    private final boolean billingEnabled;

    public BillingWebhookService(
            org.springframework.beans.factory.ObjectProvider<StripeBillingProvider> stripeProvider,
            org.springframework.beans.factory.ObjectProvider<RazorpayBillingProvider> razorpayProvider,
            BillingEventRepository billingEventRepository,
            BillingEventClaimer billingEventClaimer,
            SubscriptionRepository subscriptionRepository,
            EntitlementService entitlementService,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Value("${flowops.billing.enabled:false}") boolean billingEnabled) {
        this.stripeProvider = stripeProvider;
        this.razorpayProvider = razorpayProvider;
        this.billingEventRepository = billingEventRepository;
        this.billingEventClaimer = billingEventClaimer;
        this.subscriptionRepository = subscriptionRepository;
        this.entitlementService = entitlementService;
        this.meterRegistry = meterRegistry;
        this.billingEnabled = billingEnabled;
    }

    /**
     * Verifies raw signature, enforces idempotency, guards against out-of-order regression,
     * and updates subscription state.
     */
    @Transactional
    public WebhookProcessResult processWebhook(String providerName, String rawPayload, Map<String, String> headers) {
        String providerKey = providerName != null ? providerName.trim().toLowerCase(Locale.ROOT) : "";

        BillingProvider provider = resolveProvider(providerKey);
        incrementCounter("billing.webhook.received.count", "provider", providerKey);

        // Step 1: Raw signature verification and parsing via provider
        WebhookEventResult eventResult;
        try {
            eventResult = provider.verifyAndParseWebhook(rawPayload, headers);
        } catch (BillingSignatureVerificationException | BillingValidationException e) {
            incrementCounter("billing.webhook.rejected.count", "provider", providerKey, "reason", "signature_failed");
            log.warn("Billing webhook rejected [provider={}]: {}", providerKey, e.getMessage());
            throw e;
        }

        String eventId = eventResult.eventId();
        String eventType = eventResult.eventType();
        Instant eventTimestamp = eventResult.timestamp() != null ? eventResult.timestamp() : Instant.now();

        // Step 2 & 3: Atomic claim in billing_events via isolated transaction
        UUID organizationId = extractOrganizationId(eventResult);
        Optional<BillingEvent> claimedEventOpt = billingEventClaimer.tryClaim(
                providerKey.toUpperCase(Locale.ROOT),
                eventId,
                eventType,
                eventTimestamp,
                rawPayload,
                organizationId
        );

        if (claimedEventOpt.isEmpty()) {
            log.info("Duplicate webhook event detected [provider={}, eventId={}]. Safely ignoring duplicate delivery.",
                    providerKey, eventId);
            incrementCounter("billing.webhook.duplicate.count", "provider", providerKey);
            return WebhookProcessResult.ignoredDuplicate(eventId);
        }

        BillingEvent billingEvent = claimedEventOpt.get();

        // Step 4: Resolve targeted subscription
        Optional<Subscription> subscriptionOpt = findSubscription(eventResult, organizationId);
        if (subscriptionOpt.isEmpty()) {
            log.warn("No subscription found matching webhook [provider={}, eventId={}, subId={}, custId={}, orgId={}]. Event recorded.",
                    providerKey, eventId, eventResult.providerSubscriptionId(), eventResult.providerCustomerId(), organizationId);
            billingEvent.setStatus("PROCESSED");
            billingEvent.setProcessedAt(Instant.now());
            billingEventRepository.save(billingEvent);
            return WebhookProcessResult.processed(eventId, "UNKNOWN_SUBSCRIPTION");
        }

        Subscription subscription = subscriptionOpt.get();

        // Step 5: Protect against out-of-order events
        if (subscription.getLastWebhookEventAt() != null
                && !eventTimestamp.isAfter(subscription.getLastWebhookEventAt())) {
            log.warn("Out-of-order webhook event rejected [provider={}, eventId={}, eventTime={}, lastWebhookTime={}]. Protecting subscription state.",
                    providerKey, eventId, eventTimestamp, subscription.getLastWebhookEventAt());
            billingEvent.setStatus("IGNORED");
            billingEvent.setErrorMessage("Out-of-order event: timestamp " + eventTimestamp + " is older than or equal to last applied webhook " + subscription.getLastWebhookEventAt());
            billingEvent.setProcessedAt(Instant.now());
            billingEventRepository.save(billingEvent);
            incrementCounter("billing.webhook.out_of_order.count", "provider", providerKey);
            return WebhookProcessResult.ignoredOutOfOrder(eventId);
        }

        // Step 6: Synchronize subscription state based on event type
        applySubscriptionStateTransition(subscription, eventResult, eventTimestamp, providerKey);
        subscriptionRepository.save(subscription);

        // Evict cached plan context
        entitlementService.evictPlanCache(subscription.getWorkspaceId());

        // Step 7: Mark billing_events record as PROCESSED
        billingEvent.setStatus("PROCESSED");
        billingEvent.setProcessedAt(Instant.now());
        billingEventRepository.save(billingEvent);

        incrementCounter("billing.webhook.processed.count", "provider", providerKey, "eventType", eventType);
        log.info("Successfully applied webhook state [provider={}, eventId={}, eventType={}, subId={}, status={}]",
                providerKey, eventId, eventType, subscription.getId(), subscription.getStatus());

        return WebhookProcessResult.processed(eventId, subscription.getStatus());
    }

    private BillingProvider resolveProvider(String providerKey) {
        if (!billingEnabled) {
            throw new BillingValidationException("Billing is currently disabled", "BILLING_DISABLED");
        }
        if ("stripe".equals(providerKey)) {
            StripeBillingProvider provider = stripeProvider.getIfAvailable();
            if (provider == null) {
                throw new BillingValidationException("Stripe billing provider is not available", "PROVIDER_UNAVAILABLE");
            }
            return provider;
        } else if ("razorpay".equals(providerKey)) {
            RazorpayBillingProvider provider = razorpayProvider.getIfAvailable();
            if (provider == null) {
                throw new BillingValidationException("Razorpay billing provider is not available", "PROVIDER_UNAVAILABLE");
            }
            return provider;
        } else {
            throw new BillingValidationException("Unsupported webhook provider: " + providerKey, "UNSUPPORTED_PROVIDER");
        }
    }

    private Optional<Subscription> findSubscription(WebhookEventResult eventResult, UUID organizationId) {
        if (eventResult.providerSubscriptionId() != null && !eventResult.providerSubscriptionId().isBlank()) {
            Optional<Subscription> sub = subscriptionRepository.findFirstByProviderSubscriptionIdOrderByCreatedAtDesc(eventResult.providerSubscriptionId());
            if (sub.isPresent()) return sub;
        }

        if (eventResult.providerCustomerId() != null && !eventResult.providerCustomerId().isBlank()) {
            Optional<Subscription> sub = subscriptionRepository.findFirstByProviderCustomerIdOrderByCreatedAtDesc(eventResult.providerCustomerId());
            if (sub.isPresent()) return sub;
        }

        if (organizationId != null) {
            return subscriptionRepository.findByWorkspaceId(organizationId);
        }

        return Optional.empty();
    }

    private UUID extractOrganizationId(WebhookEventResult eventResult) {
        if (eventResult.metadata() != null) {
            Object orgIdObj = eventResult.metadata().get("organizationId");
            if (orgIdObj == null) {
                orgIdObj = eventResult.metadata().get("org_id");
            }
            if (orgIdObj == null) {
                orgIdObj = eventResult.metadata().get("clientReferenceId");
            }
            if (orgIdObj != null) {
                try {
                    return UUID.fromString(orgIdObj.toString());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    private void applySubscriptionStateTransition(
            Subscription subscription,
            WebhookEventResult eventResult,
            Instant eventTimestamp,
            String providerKey) {

        String eventType = eventResult.eventType();
        subscription.setProvider(providerKey.toUpperCase(Locale.ROOT));
        subscription.setLastWebhookEventAt(eventTimestamp);

        if (eventResult.providerSubscriptionId() != null && !eventResult.providerSubscriptionId().isBlank()) {
            subscription.setProviderSubscriptionId(eventResult.providerSubscriptionId());
        }
        if (eventResult.providerCustomerId() != null && !eventResult.providerCustomerId().isBlank()) {
            subscription.setProviderCustomerId(eventResult.providerCustomerId());
        }

        if (isPaymentSuccessEvent(eventType)) {
            // Successful payment clears dunning state and keeps subscription active
            subscription.setStatus("ACTIVE");
            subscription.setPastDueSince(null);
        } else if (isPaymentFailedEvent(eventType)) {
            // Payment failure initiates PAST_DUE and 7-day dunning grace period
            subscription.setStatus("PAST_DUE");
            if (subscription.getPastDueSince() == null) {
                subscription.setPastDueSince(eventTimestamp);
            }
        } else if (isSubscriptionCanceledEvent(eventType)) {
            // Cancellation / deletion
            subscription.setStatus("CANCELED");
            subscription.setCancelAtPeriodEnd(false);
        } else if (isSubscriptionCreatedOrUpdated(eventType)) {
            String eventStatus = eventResult.status();
            if ("PAST_DUE".equalsIgnoreCase(eventStatus)) {
                subscription.setStatus("PAST_DUE");
                if (subscription.getPastDueSince() == null) {
                    subscription.setPastDueSince(eventTimestamp);
                }
            } else if ("CANCELED".equalsIgnoreCase(eventStatus)) {
                subscription.setStatus("CANCELED");
                subscription.setCancelAtPeriodEnd(false);
            } else {
                subscription.setStatus("ACTIVE");
                subscription.setPastDueSince(null);
            }

            if (eventResult.metadata() != null && eventResult.metadata().containsKey("cancelAtPeriodEnd")) {
                boolean cancelAtPeriodEnd = Boolean.parseBoolean(eventResult.metadata().get("cancelAtPeriodEnd").toString());
                subscription.setCancelAtPeriodEnd(cancelAtPeriodEnd);
            }

            if (eventResult.planId() != null && !eventResult.planId().isBlank()) {
                String planName = mapPlanName(eventResult.planId());
                if (planName != null) {
                    subscription.setPlan(planName);
                }
            }
        }
    }

    private boolean isPaymentSuccessEvent(String eventType) {
        return "invoice.paid".equalsIgnoreCase(eventType)
                || "invoice.payment_succeeded".equalsIgnoreCase(eventType)
                || "subscription.charged".equalsIgnoreCase(eventType)
                || "order.paid".equalsIgnoreCase(eventType);
    }

    private boolean isPaymentFailedEvent(String eventType) {
        return "invoice.payment_failed".equalsIgnoreCase(eventType)
                || "payment.failed".equalsIgnoreCase(eventType)
                || "subscription.pending".equalsIgnoreCase(eventType)
                || "subscription.halted".equalsIgnoreCase(eventType);
    }

    private boolean isSubscriptionCanceledEvent(String eventType) {
        return "customer.subscription.deleted".equalsIgnoreCase(eventType)
                || "subscription.cancelled".equalsIgnoreCase(eventType)
                || "subscription.completed".equalsIgnoreCase(eventType);
    }

    private boolean isSubscriptionCreatedOrUpdated(String eventType) {
        return "customer.subscription.created".equalsIgnoreCase(eventType)
                || "customer.subscription.updated".equalsIgnoreCase(eventType)
                || "checkout.session.completed".equalsIgnoreCase(eventType)
                || "subscription.activated".equalsIgnoreCase(eventType)
                || "subscription.authenticated".equalsIgnoreCase(eventType);
    }

    private String mapPlanName(String planOrPriceId) {
        String lower = planOrPriceId.toLowerCase(Locale.ROOT);
        if (lower.contains("business")) {
            return "BUSINESS";
        }
        if (lower.contains("pro")) {
            return "PRO";
        }
        return null;
    }

    private void incrementCounter(String metricName, String... tags) {
        if (meterRegistry != null) {
            meterRegistry.counter(metricName, tags).increment();
        }
    }
}
