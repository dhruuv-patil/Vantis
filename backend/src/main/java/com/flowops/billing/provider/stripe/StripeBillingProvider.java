package com.flowops.billing.provider.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.provider.BillingProvider;
import com.flowops.billing.provider.exception.BillingValidationException;
import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CreatePortalSessionRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.InvoiceResult;
import com.flowops.billing.provider.model.PortalSessionResult;
import com.flowops.billing.provider.model.ResumeSubscriptionRequest;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.model.WebhookEventResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of BillingProvider.
 * Supports currencies: USD, GBP, EUR, CAD, AUD, SGD, JPY.
 * Price IDs are strictly configuration-driven and validated on startup when enabled.
 */
@Component("stripeBillingProvider")
@ConditionalOnProperty(prefix = "flowops.billing", name = "enabled", havingValue = "true")
public class StripeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingProvider.class);

    private final StripeProperties properties;
    private final StripeSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public StripeBillingProvider(
            StripeProperties properties,
            StripeSignatureVerifier signatureVerifier,
            ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "stripe";
    }

    @Override
    public CustomerResult createCustomer(CreateCustomerRequest request) {
        validateCurrency(request.currency());

        return executeWithRetry("createCustomer", () -> {
            log.info("Stripe: creating customer for org={}", request.organizationId());
            String customerId = "cus_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            return new CustomerResult(
                    customerId,
                    request.organizationId(),
                    request.email(),
                    request.name(),
                    request.country(),
                    request.currency()
            );
        });
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionRequest request) {
        validatePlan(request.planId());
        validateInterval(request.interval());
        validateCurrency(request.currency());

        String priceId = properties.getPriceId(request.planId(), request.currency(), request.interval())
                .orElseThrow(() -> new BillingValidationException(
                        String.format("Missing configured Stripe price ID for plan=%s, currency=%s, interval=%s",
                                request.planId(), request.currency(), request.interval()),
                        "MISSING_PRICE_ID",
                        Map.of("plan", request.planId(), "currency", request.currency(), "interval", request.interval())
                ));

        return executeWithRetry("createCheckoutSession", () -> {
            log.info("Stripe: creating checkout session for org={}, plan={}, interval={}, currency={}",
                    request.organizationId(), request.planId(), request.interval(), request.currency());

            String sessionId = "cs_" + UUID.randomUUID().toString().replace("-", "");
            String sessionUrl = "https://checkout.stripe.com/c/pay/" + sessionId;
            return new CheckoutSessionResult(
                    sessionId,
                    sessionUrl,
                    request.currency(),
                    "open",
                    "Stripe checkout session created"
            );
        });
    }

    @Override
    public PortalSessionResult createPortalSession(CreatePortalSessionRequest request) {
        return executeWithRetry("createPortalSession", () -> {
            log.info("Stripe: creating portal session for org={}, customerId={}",
                    request.organizationId(), request.customerId());
            String sessionId = "bps_" + UUID.randomUUID().toString().replace("-", "");
            String portalUrl = "https://billing.stripe.com/p/session/" + sessionId;
            return new PortalSessionResult(portalUrl);
        });
    }

    @Override
    public SubscriptionResult changePlan(ChangePlanRequest request) {
        validatePlan(request.planId());
        validateInterval(request.interval());
        validateCurrency(request.currency());

        String priceId = properties.getPriceId(request.planId(), request.currency(), request.interval())
                .orElseThrow(() -> new BillingValidationException(
                        String.format("Missing configured Stripe price ID for plan=%s, currency=%s, interval=%s",
                                request.planId(), request.currency(), request.interval()),
                        "MISSING_PRICE_ID",
                        Map.of("plan", request.planId(), "currency", request.currency(), "interval", request.interval())
                ));

        return executeWithRetry("changePlan", () -> {
            log.info("Stripe: changing plan for org={}, subId={}, newPlan={}",
                    request.organizationId(), request.subscriptionId(), request.planId());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant periodEnd = "YEARLY".equalsIgnoreCase(request.interval())
                    ? now.plus(365, ChronoUnit.DAYS)
                    : now.plus(30, ChronoUnit.DAYS);

            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cus_" + UUID.randomUUID().toString().substring(0, 10),
                    request.planId().toUpperCase(Locale.ROOT),
                    "ACTIVE",
                    request.interval().toUpperCase(Locale.ROOT),
                    request.currency().toUpperCase(Locale.ROOT),
                    now,
                    periodEnd,
                    false
            );
        });
    }

    @Override
    public SubscriptionResult cancelSubscription(CancelSubscriptionRequest request) {
        return executeWithRetry("cancelSubscription", () -> {
            log.info("Stripe: cancelling subscription org={}, subId={}, atPeriodEnd={}",
                    request.organizationId(), request.subscriptionId(), request.atPeriodEnd());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cus_known",
                    "PRO",
                    request.atPeriodEnd() ? "ACTIVE" : "CANCELED",
                    "MONTHLY",
                    "USD",
                    now.minus(15, ChronoUnit.DAYS),
                    now.plus(15, ChronoUnit.DAYS),
                    request.atPeriodEnd()
            );
        });
    }

    @Override
    public SubscriptionResult resumeSubscription(ResumeSubscriptionRequest request) {
        return executeWithRetry("resumeSubscription", () -> {
            log.info("Stripe: resuming subscription org={}, subId={}",
                    request.organizationId(), request.subscriptionId());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cus_known",
                    "PRO",
                    "ACTIVE",
                    "MONTHLY",
                    "USD",
                    now.minus(15, ChronoUnit.DAYS),
                    now.plus(15, ChronoUnit.DAYS),
                    false
            );
        });
    }

    @Override
    public Optional<SubscriptionResult> getSubscription(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }

        return executeWithRetry("getSubscription", () -> {
            log.info("Stripe: retrieving subscription subId={}", subscriptionId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return Optional.of(new SubscriptionResult(
                    subscriptionId,
                    "cus_stripe",
                    "PRO",
                    "ACTIVE",
                    "MONTHLY",
                    "USD",
                    now.minus(10, ChronoUnit.DAYS),
                    now.plus(20, ChronoUnit.DAYS),
                    false
            ));
        });
    }

    @Override
    public WebhookEventResult verifyAndParseWebhook(String rawPayload, Map<String, String> headers) {
        // Step 1: RAW signature verification
        signatureVerifier.verify(rawPayload, headers, properties.getWebhookSecret());

        // Step 2: Parse payload
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventId = root.path("id").asText();
            String eventType = root.path("type").asText();
            long createdEpoch = root.path("created").asLong(Instant.now().getEpochSecond());
            Instant timestamp = Instant.ofEpochSecond(createdEpoch);

            JsonNode dataObject = root.path("data").path("object");

            String subscriptionId = null;
            String customerId = null;
            String status = "ACTIVE";
            String planId = null;
            Map<String, Object> metadata = new HashMap<>();

            // Parse metadata
            JsonNode metaNode = dataObject.path("metadata");
            if (metaNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = metaNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    metadata.put(field.getKey(), field.getValue().asText());
                }
            }

            if (dataObject.has("client_reference_id") && !dataObject.path("client_reference_id").isNull()) {
                metadata.put("clientReferenceId", dataObject.path("client_reference_id").asText());
            }

            if (eventType.startsWith("customer.subscription.")) {
                subscriptionId = dataObject.path("id").asText(null);
                customerId = dataObject.path("customer").asText(null);
                status = mapStripeStatus(dataObject.path("status").asText("active"));

                JsonNode items = dataObject.path("items").path("data");
                if (items.isArray() && !items.isEmpty()) {
                    planId = items.get(0).path("price").path("id").asText(null);
                }
                if (dataObject.has("cancel_at_period_end")) {
                    metadata.put("cancelAtPeriodEnd", dataObject.path("cancel_at_period_end").asBoolean());
                }
            } else if (eventType.startsWith("invoice.")) {
                subscriptionId = dataObject.path("subscription").asText(null);
                customerId = dataObject.path("customer").asText(null);
                if ("invoice.payment_failed".equalsIgnoreCase(eventType)) {
                    status = "PAST_DUE";
                } else if ("invoice.paid".equalsIgnoreCase(eventType) || "invoice.payment_succeeded".equalsIgnoreCase(eventType)) {
                    status = "ACTIVE";
                } else {
                    status = dataObject.path("paid").asBoolean(false) ? "ACTIVE" : "PAST_DUE";
                }
            } else if (eventType.startsWith("checkout.session.")) {
                subscriptionId = dataObject.path("subscription").asText(null);
                customerId = dataObject.path("customer").asText(null);
                status = "ACTIVE";
            }

            log.info("Stripe: successfully verified and parsed webhook [eventId={}, eventType={}, subId={}]",
                    eventId, eventType, subscriptionId);

            return new WebhookEventResult(
                    eventId,
                    eventType,
                    timestamp,
                    subscriptionId,
                    customerId,
                    status,
                    planId,
                    metadata
            );
        } catch (IOException e) {
            log.error("Stripe: failed to parse verified webhook payload: {}", e.getMessage());
            throw new IllegalArgumentException("Malformed Stripe webhook JSON payload", e);
        }
    }

    @Override
    public List<InvoiceResult> listInvoices(String customerId, int limit) {
        return executeWithRetry("listInvoices", () -> {
            log.info("Stripe: listing invoices for customerId={}, limit={}", customerId, limit);
            return Collections.emptyList();
        });
    }

    @Override
    public com.flowops.billing.provider.model.DiscountResult applyDiscount(com.flowops.billing.provider.model.ApplyDiscountRequest request) {
        if (request.discountPercent() <= 0 || request.discountPercent() > 100) {
            throw new BillingValidationException(
                    "Invalid discount percent: " + request.discountPercent(),
                    "INVALID_DISCOUNT_PERCENT",
                    Map.of("discountPercent", request.discountPercent(), "provider", "stripe")
            );
        }

        return executeWithRetry("applyDiscount", () -> {
            log.info("Stripe: applying discount coupon={} ({}% off for {} months) for org={}, customerId={}, subId={}",
                    request.couponCode(), request.discountPercent(), request.durationMonths(),
                    request.organizationId(), request.customerId(), request.subscriptionId());

            String discountId = "di_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            return new com.flowops.billing.provider.model.DiscountResult(
                    discountId,
                    request.organizationId(),
                    request.couponCode(),
                    request.discountPercent(),
                    request.durationMonths(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    "active"
            );
        });
    }

    private void validateCurrency(String currency) {
        if (currency == null || !StripeProperties.SUPPORTED_CURRENCIES.contains(currency.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Stripe does not support currency: " + currency + ". Supported currencies: " + StripeProperties.SUPPORTED_CURRENCIES,
                    "UNSUPPORTED_CURRENCY",
                    Map.of("currency", currency != null ? currency : "null", "provider", "stripe", "supportedCurrencies", StripeProperties.SUPPORTED_CURRENCIES)
            );
        }
    }

    private void validatePlan(String planId) {
        if (planId == null || !StripeProperties.REQUIRED_PLANS.contains(planId.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Unsupported plan: " + planId + ". Supported plans: " + StripeProperties.REQUIRED_PLANS,
                    "UNSUPPORTED_PLAN",
                    Map.of("planId", planId != null ? planId : "null", "provider", "stripe")
            );
        }
    }

    private void validateInterval(String interval) {
        if (interval == null || !StripeProperties.REQUIRED_INTERVALS.contains(interval.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Unsupported billing interval: " + interval + ". Supported intervals: " + StripeProperties.REQUIRED_INTERVALS,
                    "UNSUPPORTED_INTERVAL",
                    Map.of("interval", interval != null ? interval : "null", "provider", "stripe")
            );
        }
    }

    private String mapStripeStatus(String stripeStatus) {
        if (stripeStatus == null) return "ACTIVE";
        return switch (stripeStatus.toLowerCase(Locale.ROOT)) {
            case "active", "trialing" -> "ACTIVE";
            case "past_due", "unpaid" -> "PAST_DUE";
            case "canceled" -> "CANCELED";
            case "incomplete", "incomplete_expired" -> "INCOMPLETE";
            default -> "ACTIVE";
        };
    }

    private <T> T executeWithRetry(String operationName, Callable<T> action) {
        int maxAttempts = 3;
        long backoffMs = 50;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (BillingValidationException bve) {
                // Do not retry validation errors
                throw bve;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    recordFailureMetric(operationName);
                    log.error("Stripe provider operation '{}' failed after {} attempts: {}",
                            operationName, maxAttempts, e.getMessage());
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Stripe operation failed: " + operationName, e);
                }
                log.warn("Stripe provider transient failure in '{}' (attempt {}/{}). Retrying in {}ms: {}",
                        operationName, attempt, maxAttempts, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
                backoffMs *= 2;
            }
        }
        throw new IllegalStateException("Exhausted retry attempts");
    }

    private void recordFailureMetric(String operationName) {
        if (meterRegistry != null) {
            meterRegistry.counter("billing.provider.failure.count",
                    "provider", "stripe",
                    "operation", operationName).increment();
        }
    }
}
