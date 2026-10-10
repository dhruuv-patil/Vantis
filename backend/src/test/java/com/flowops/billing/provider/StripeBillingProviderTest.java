package com.flowops.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.provider.exception.BillingConfigurationException;
import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import com.flowops.billing.provider.exception.BillingValidationException;
import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.model.WebhookEventResult;
import com.flowops.billing.provider.stripe.StripeBillingProvider;
import com.flowops.billing.provider.stripe.StripeProperties;
import com.flowops.billing.provider.stripe.StripeSignatureVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StripeBillingProviderTest {

    private StripeProperties properties;
    private StripeSignatureVerifier verifier;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private StripeBillingProvider provider;

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_secret_1234567890abcdef";
    private static final String TEST_STRIPE_SECRET = "sk_test_1234567890abcdef";

    @BeforeEach
    void setUp() {
        properties = new StripeProperties();
        properties.setSecretKey(TEST_STRIPE_SECRET);
        properties.setWebhookSecret(TEST_WEBHOOK_SECRET);

        // Populate valid prices for all 28 combinations (PRO/BUSINESS x 7 currencies x MONTHLY/YEARLY)
        Map<String, String> prices = new HashMap<>();
        String[] plans = {"pro", "business"};
        String[] currencies = {"usd", "gbp", "eur", "cad", "aud", "sgd", "jpy"};
        String[] intervals = {"monthly", "yearly"};

        for (String plan : plans) {
            for (String curr : currencies) {
                for (String interval : intervals) {
                    prices.put(plan + "." + curr + "." + interval, "price_" + plan + "_" + curr + "_" + interval);
                }
            }
        }
        properties.setPrices(prices);

        verifier = new StripeSignatureVerifier();
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        provider = new StripeBillingProvider(properties, verifier, objectMapper, meterRegistry);
    }

    @Test
    @DisplayName("Stripe supports USD, GBP, EUR, CAD, AUD, SGD, JPY")
    void supportedCurrencies() {
        UUID orgId = UUID.randomUUID();
        for (String currency : StripeProperties.SUPPORTED_CURRENCIES) {
            CustomerResult cust = provider.createCustomer(new CreateCustomerRequest(
                    orgId, "user@example.com", "Test Org", "US", currency));
            assertThat(cust.currency()).isEqualTo(currency);
        }
    }

    @Test
    @DisplayName("Stripe rejects unsupported currency (e.g. INR) with structured BillingValidationException")
    void unsupportedCurrencyRejected() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> provider.createCustomer(new CreateCustomerRequest(
                orgId, "user@example.com", "Test Org", "IN", "INR")))
                .isInstanceOf(BillingValidationException.class)
                .hasMessageContaining("Stripe does not support currency: INR")
                .satisfies(ex -> {
                    BillingValidationException bve = (BillingValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("UNSUPPORTED_CURRENCY");
                    assertThat(bve.getDetails()).containsEntry("currency", "INR");
                });
    }

    @Test
    @DisplayName("Stripe rejects unsupported plan with structured BillingValidationException")
    void unsupportedPlanRejected() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> provider.createCheckoutSession(new CreateCheckoutSessionRequest(
                orgId, "cus_123", "ENTERPRISE", "MONTHLY", "USD", "https://success", "https://cancel")))
                .isInstanceOf(BillingValidationException.class)
                .hasMessageContaining("Unsupported plan: ENTERPRISE");
    }

    @Test
    @DisplayName("Stripe checkout session succeeds with valid configuration")
    void createCheckoutSessionSuccess() {
        UUID orgId = UUID.randomUUID();
        CheckoutSessionResult result = provider.createCheckoutSession(new CreateCheckoutSessionRequest(
                orgId, "cus_123", "PRO", "MONTHLY", "USD", "https://success", "https://cancel"));

        assertThat(result.sessionId()).startsWith("cs_");
        assertThat(result.url()).contains(result.sessionId());
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.status()).isEqualTo("open");
    }

    @Test
    @DisplayName("Stripe signature verifier accepts valid HMAC-SHA256 signature")
    void validStripeWebhookSignature() {
        String rawPayload = "{\"id\":\"evt_test1\",\"type\":\"customer.subscription.updated\",\"created\":1620000000,\"data\":{\"object\":{\"id\":\"sub_123\",\"status\":\"active\"}}}";
        String timestamp = "1620000000";
        String signature = StripeSignatureVerifier.computeHmacSha256(timestamp + "." + rawPayload, TEST_WEBHOOK_SECRET);
        String headerVal = "t=" + timestamp + ",v1=" + signature;

        Map<String, String> headers = Map.of("Stripe-Signature", headerVal);
        WebhookEventResult event = provider.verifyAndParseWebhook(rawPayload, headers);

        assertThat(event.eventId()).isEqualTo("evt_test1");
        assertThat(event.eventType()).isEqualTo("customer.subscription.updated");
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_123");
        assertThat(event.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Stripe rejects invalid or tampered signature")
    void invalidStripeSignatureRejected() {
        String rawPayload = "{\"id\":\"evt_test1\",\"type\":\"customer.subscription.updated\"}";
        String timestamp = "1620000000";
        String forgedSignature = "badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbad";
        Map<String, String> headers = Map.of("Stripe-Signature", "t=" + timestamp + ",v1=" + forgedSignature);

        assertThatThrownBy(() -> provider.verifyAndParseWebhook(rawPayload, headers))
                .isInstanceOf(BillingSignatureVerificationException.class)
                .hasMessageContaining("Stripe webhook signature verification failed");
    }

    @Test
    @DisplayName("Stripe startup configuration validation fails if required price ID is missing")
    void startupValidationFailsOnMissingPriceId() {
        // Remove one required price ID (e.g. business.jpy.yearly)
        properties.getPrices().remove("business.jpy.yearly");

        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Missing required Stripe price ID for plan=BUSINESS, currency=JPY, interval=YEARLY");
    }

    @Test
    @DisplayName("Stripe startup configuration validation fails if secret key or webhook secret is missing")
    void startupValidationFailsOnMissingSecret() {
        properties.setSecretKey("");
        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("flowops.billing.stripe.secret-key is required");

        properties.setSecretKey(TEST_STRIPE_SECRET);
        properties.setWebhookSecret("");
        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("flowops.billing.stripe.webhook-secret is required");
    }

    @Test
    @DisplayName("Stripe plan change and cancellation operations")
    void planChangeAndCancellation() {
        UUID orgId = UUID.randomUUID();
        SubscriptionResult changed = provider.changePlan(new ChangePlanRequest(
                orgId, "sub_123", "BUSINESS", "YEARLY", "EUR"));
        assertThat(changed.planId()).isEqualTo("BUSINESS");
        assertThat(changed.interval()).isEqualTo("YEARLY");
        assertThat(changed.currency()).isEqualTo("EUR");

        SubscriptionResult cancelled = provider.cancelSubscription(new CancelSubscriptionRequest(
                orgId, "sub_123", true));
        assertThat(cancelled.cancelAtPeriodEnd()).isTrue();
    }

    @Test
    @DisplayName("Stripe applyDiscount: applies 30% discount for 12 months with FOUNDING_30 coupon")
    void applyDiscountSuccess() {
        UUID orgId = UUID.randomUUID();
        com.flowops.billing.provider.model.ApplyDiscountRequest req =
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        orgId, "cus_123", "sub_123", "FOUNDING_30", 30, 12);
        com.flowops.billing.provider.model.DiscountResult res = provider.applyDiscount(req);
        assertThat(res).isNotNull();
        assertThat(res.organizationId()).isEqualTo(orgId);
        assertThat(res.couponCode()).isEqualTo("FOUNDING_30");
        assertThat(res.discountPercent()).isEqualTo(30);
        assertThat(res.durationMonths()).isEqualTo(12);
        assertThat(res.status()).isEqualTo("active");
        assertThat(res.discountId()).startsWith("di_");
    }

    @Test
    @DisplayName("Stripe applyDiscount: rejects invalid discount percent")
    void applyDiscountRejectsInvalidPercent() {
        UUID orgId = UUID.randomUUID();
        com.flowops.billing.provider.model.ApplyDiscountRequest req =
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        orgId, "cus_123", "sub_123", "FOUNDING_30", -5, 12);
        assertThatThrownBy(() -> provider.applyDiscount(req))
                .isInstanceOf(com.flowops.billing.provider.exception.BillingValidationException.class)
                .hasMessageContaining("Invalid discount percent");
    }
}
