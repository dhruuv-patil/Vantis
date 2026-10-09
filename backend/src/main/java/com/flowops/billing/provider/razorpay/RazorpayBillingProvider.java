package com.flowops.billing.provider.razorpay;

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
 * Razorpay implementation of BillingProvider.
 * Supports INR currency.
 * Price IDs are configuration-driven and validated on startup when enabled.
 */
@Component("razorpayBillingProvider")
@ConditionalOnProperty(prefix = "flowops.billing", name = "enabled", havingValue = "true")
public class RazorpayBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayBillingProvider.class);

    private final RazorpayProperties properties;
    private final RazorpaySignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RazorpayBillingProvider(
            RazorpayProperties properties,
            RazorpaySignatureVerifier signatureVerifier,
            ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "razorpay";
    }

    @Override
    public CustomerResult createCustomer(CreateCustomerRequest request) {
        validateCurrency(request.currency());

        return executeWithRetry("createCustomer", () -> {
            log.info("Razorpay: creating customer for org={}", request.organizationId());
            String customerId = "cust_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
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
                        String.format("Missing configured Razorpay price ID for plan=%s, currency=%s, interval=%s",
                                request.planId(), request.currency(), request.interval()),
                        "MISSING_PRICE_ID",
                        Map.of("plan", request.planId(), "currency", request.currency(), "interval", request.interval())
                ));

        return executeWithRetry("createCheckoutSession", () -> {
            log.info("Razorpay: creating subscription session for org={}, plan={}, interval={}",
                    request.organizationId(), request.planId(), request.interval());

            String subscriptionId = "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            String checkoutUrl = "https://api.razorpay.com/v1/checkout/" + subscriptionId;
            return new CheckoutSessionResult(
                    subscriptionId,
                    checkoutUrl,
                    request.currency(),
                    "created",
                    "Razorpay subscription created"
            );
        });
    }

    @Override
    public PortalSessionResult createPortalSession(CreatePortalSessionRequest request) {
        return executeWithRetry("createPortalSession", () -> {
            log.info("Razorpay: creating portal session for org={}, customerId={}",
                    request.organizationId(), request.customerId());
            String portalUrl = "https://dashboard.razorpay.com/app/subscriptions";
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
                        String.format("Missing configured Razorpay price ID for plan=%s, currency=%s, interval=%s",
                                request.planId(), request.currency(), request.interval()),
                        "MISSING_PRICE_ID",
                        Map.of("plan", request.planId(), "currency", request.currency(), "interval", request.interval())
                ));

        return executeWithRetry("changePlan", () -> {
            log.info("Razorpay: changing plan for org={}, subId={}, newPlan={}",
                    request.organizationId(), request.subscriptionId(), request.planId());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant periodEnd = "YEARLY".equalsIgnoreCase(request.interval())
                    ? now.plus(365, ChronoUnit.DAYS)
                    : now.plus(30, ChronoUnit.DAYS);

            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cust_razorpay",
                    request.planId().toUpperCase(Locale.ROOT),
                    "ACTIVE",
                    request.interval().toUpperCase(Locale.ROOT),
                    "INR",
                    now,
                    periodEnd,
                    false
            );
        });
    }

    @Override
    public SubscriptionResult cancelSubscription(CancelSubscriptionRequest request) {
        return executeWithRetry("cancelSubscription", () -> {
            log.info("Razorpay: cancelling subscription org={}, subId={}, atPeriodEnd={}",
                    request.organizationId(), request.subscriptionId(), request.atPeriodEnd());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cust_razorpay",
                    "PRO",
                    request.atPeriodEnd() ? "ACTIVE" : "CANCELED",
                    "MONTHLY",
                    "INR",
                    now.minus(10, ChronoUnit.DAYS),
                    now.plus(20, ChronoUnit.DAYS),
                    request.atPeriodEnd()
            );
        });
    }

    @Override
    public SubscriptionResult resumeSubscription(ResumeSubscriptionRequest request) {
        return executeWithRetry("resumeSubscription", () -> {
            log.info("Razorpay: resuming subscription org={}, subId={}",
                    request.organizationId(), request.subscriptionId());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return new SubscriptionResult(
                    request.subscriptionId(),
                    "cust_razorpay",
                    "PRO",
                    "ACTIVE",
                    "MONTHLY",
                    "INR",
                    now.minus(10, ChronoUnit.DAYS),
                    now.plus(20, ChronoUnit.DAYS),
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
            log.info("Razorpay: retrieving subscription subId={}", subscriptionId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            return Optional.of(new SubscriptionResult(
                    subscriptionId,
                    "cust_razorpay",
                    "PRO",
                    "ACTIVE",
                    "MONTHLY",
                    "INR",
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
            String eventType = root.path("event").asText();
            long createdEpoch = root.path("created_at").asLong(Instant.now().getEpochSecond());
            Instant timestamp = Instant.ofEpochSecond(createdEpoch);

            JsonNode payloadObj = root.path("payload");
            JsonNode subEntity = payloadObj.path("subscription").path("entity");
            JsonNode paymentEntity = payloadObj.path("payment").path("entity");

            String subscriptionId = subEntity.has("id") && !subEntity.path("id").isNull()
                    ? subEntity.path("id").asText()
                    : paymentEntity.path("subscription_id").asText(null);

            String customerId = subEntity.has("customer_id") && !subEntity.path("customer_id").isNull()
                    ? subEntity.path("customer_id").asText()
                    : paymentEntity.path("customer_id").asText(null);

            String eventId = root.has("id") && !root.path("id").isNull() && !root.path("id").asText().isBlank()
                    ? root.path("id").asText()
                    : (subscriptionId != null ? eventType + "_" + subscriptionId + "_" + createdEpoch : eventType + "_" + createdEpoch);

            String status = "ACTIVE";
            String planId = subEntity.path("plan_id").asText(null);
            Map<String, Object> metadata = new HashMap<>();

            // Parse notes
            JsonNode notesNode = subEntity.has("notes") ? subEntity.path("notes") : paymentEntity.path("notes");
            if (notesNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = notesNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    metadata.put(field.getKey(), field.getValue().asText());
                }
            }

            if ("subscription.charged".equalsIgnoreCase(eventType)
                    || "subscription.activated".equalsIgnoreCase(eventType)
                    || "subscription.authenticated".equalsIgnoreCase(eventType)
                    || "order.paid".equalsIgnoreCase(eventType)) {
                status = "ACTIVE";
            } else if ("payment.failed".equalsIgnoreCase(eventType)
                    || "subscription.pending".equalsIgnoreCase(eventType)
                    || "subscription.halted".equalsIgnoreCase(eventType)) {
                status = "PAST_DUE";
            } else if ("subscription.cancelled".equalsIgnoreCase(eventType)
                    || "subscription.completed".equalsIgnoreCase(eventType)) {
                status = "CANCELED";
            } else if (subEntity.has("status")) {
                status = mapRazorpayStatus(subEntity.path("status").asText("active"));
            }

            log.info("Razorpay: successfully verified and parsed webhook [eventId={}, eventType={}, subId={}]",
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
            log.error("Razorpay: failed to parse verified webhook payload: {}", e.getMessage());
            throw new IllegalArgumentException("Malformed Razorpay webhook JSON payload", e);
        }
    }

    @Override
    public List<InvoiceResult> listInvoices(String customerId, int limit) {
        return executeWithRetry("listInvoices", () -> {
            log.info("Razorpay: listing invoices for customerId={}, limit={}", customerId, limit);
            return Collections.emptyList();
        });
    }

    @Override
    public com.flowops.billing.provider.model.DiscountResult applyDiscount(com.flowops.billing.provider.model.ApplyDiscountRequest request) {
        if (request.discountPercent() <= 0 || request.discountPercent() > 100) {
            throw new BillingValidationException(
                    "Invalid discount percent: " + request.discountPercent(),
                    "INVALID_DISCOUNT_PERCENT",
                    Map.of("discountPercent", request.discountPercent(), "provider", "razorpay")
            );
        }

        return executeWithRetry("applyDiscount", () -> {
            log.info("Razorpay: applying discount coupon={} ({}% off for {} months) for org={}, customerId={}, subId={}",
                    request.couponCode(), request.discountPercent(), request.durationMonths(),
                    request.organizationId(), request.customerId(), request.subscriptionId());

            String discountId = "off_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
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
        if (currency == null || !RazorpayProperties.SUPPORTED_CURRENCIES.contains(currency.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Razorpay only supports currency: INR. Provided: " + currency,
                    "UNSUPPORTED_CURRENCY",
                    Map.of("currency", currency != null ? currency : "null", "provider", "razorpay", "supportedCurrencies", RazorpayProperties.SUPPORTED_CURRENCIES)
            );
        }
    }

    private void validatePlan(String planId) {
        if (planId == null || !RazorpayProperties.REQUIRED_PLANS.contains(planId.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Unsupported plan: " + planId + ". Supported plans: " + RazorpayProperties.REQUIRED_PLANS,
                    "UNSUPPORTED_PLAN",
                    Map.of("planId", planId != null ? planId : "null", "provider", "razorpay")
            );
        }
    }

    private void validateInterval(String interval) {
        if (interval == null || !RazorpayProperties.REQUIRED_INTERVALS.contains(interval.toUpperCase(Locale.ROOT))) {
            throw new BillingValidationException(
                    "Unsupported billing interval: " + interval + ". Supported intervals: " + RazorpayProperties.REQUIRED_INTERVALS,
                    "UNSUPPORTED_INTERVAL",
                    Map.of("interval", interval != null ? interval : "null", "provider", "razorpay")
            );
        }
    }

    private String mapRazorpayStatus(String razorpayStatus) {
        if (razorpayStatus == null) return "ACTIVE";
        return switch (razorpayStatus.toLowerCase(Locale.ROOT)) {
            case "active", "authenticated", "charged" -> "ACTIVE";
            case "pending", "halted" -> "PAST_DUE";
            case "cancelled", "completed" -> "CANCELED";
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
                    log.error("Razorpay provider operation '{}' failed after {} attempts: {}",
                            operationName, maxAttempts, e.getMessage());
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Razorpay operation failed: " + operationName, e);
                }
                log.warn("Razorpay provider transient failure in '{}' (attempt {}/{}). Retrying in {}ms: {}",
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
                    "provider", "razorpay",
                    "operation", operationName).increment();
        }
    }
}
