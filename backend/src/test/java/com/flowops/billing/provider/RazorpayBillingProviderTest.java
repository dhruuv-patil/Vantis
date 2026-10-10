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
import com.flowops.billing.provider.razorpay.RazorpayBillingProvider;
import com.flowops.billing.provider.razorpay.RazorpayProperties;
import com.flowops.billing.provider.razorpay.RazorpaySignatureVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RazorpayBillingProviderTest {

    private RazorpayProperties properties;
    private RazorpaySignatureVerifier verifier;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private RazorpayBillingProvider provider;

    private static final String TEST_WEBHOOK_SECRET = "rzp_sec_test_1234567890abcdef";
    private static final String TEST_KEY_ID = "rzp_test_key123";
    private static final String TEST_KEY_SECRET = "rzp_test_secret123";

    @BeforeEach
    void setUp() {
        properties = new RazorpayProperties();
        properties.setKeyId(TEST_KEY_ID);
        properties.setKeySecret(TEST_KEY_SECRET);
        properties.setWebhookSecret(TEST_WEBHOOK_SECRET);

        Map<String, String> prices = new HashMap<>();
        prices.put("pro.inr.monthly", "plan_pro_monthly_inr");
        prices.put("pro.inr.yearly", "plan_pro_yearly_inr");
        prices.put("business.inr.monthly", "plan_biz_monthly_inr");
        prices.put("business.inr.yearly", "plan_biz_yearly_inr");
        properties.setPrices(prices);

        verifier = new RazorpaySignatureVerifier();
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        provider = new RazorpayBillingProvider(properties, verifier, objectMapper, meterRegistry);
    }

    @Test
    @DisplayName("Razorpay supports INR currency")
    void supportedCurrencyInr() {
        UUID orgId = UUID.randomUUID();
        CustomerResult cust = provider.createCustomer(new CreateCustomerRequest(
                orgId, "user@example.in", "India Org", "IN", "INR"));
        assertThat(cust.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("Razorpay rejects non-INR currency with structured BillingValidationException")
    void nonInrCurrencyRejected() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> provider.createCustomer(new CreateCustomerRequest(
                orgId, "user@example.com", "US Org", "US", "USD")))
                .isInstanceOf(BillingValidationException.class)
                .hasMessageContaining("Razorpay only supports currency: INR")
                .satisfies(ex -> {
                    BillingValidationException bve = (BillingValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("UNSUPPORTED_CURRENCY");
                });
    }

    @Test
    @DisplayName("Razorpay checkout session succeeds with valid configuration")
    void createCheckoutSessionSuccess() {
        UUID orgId = UUID.randomUUID();
        CheckoutSessionResult result = provider.createCheckoutSession(new CreateCheckoutSessionRequest(
                orgId, "cust_123", "PRO", "MONTHLY", "INR", "https://success", "https://cancel"));

        assertThat(result.sessionId()).startsWith("sub_");
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.status()).isEqualTo("created");
    }

    @Test
    @DisplayName("Razorpay signature verifier accepts valid HMAC-SHA256 signature")
    void validRazorpayWebhookSignature() {
        String rawPayload = "{\"entity\":\"event\",\"event\":\"subscription.charged\",\"created_at\":1620000000,\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_rzp1\",\"customer_id\":\"cust_rzp1\",\"status\":\"active\"}}}}";
        String expectedSignature = RazorpaySignatureVerifier.computeHmacSha256(rawPayload, TEST_WEBHOOK_SECRET);

        Map<String, String> headers = Map.of("X-Razorpay-Signature", expectedSignature);
        WebhookEventResult event = provider.verifyAndParseWebhook(rawPayload, headers);

        assertThat(event.eventType()).isEqualTo("subscription.charged");
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_rzp1");
        assertThat(event.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Razorpay rejects invalid or tampered signature")
    void invalidRazorpaySignatureRejected() {
        String rawPayload = "{\"event\":\"subscription.charged\"}";
        String forgedSignature = "badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbad";
        Map<String, String> headers = Map.of("X-Razorpay-Signature", forgedSignature);

        assertThatThrownBy(() -> provider.verifyAndParseWebhook(rawPayload, headers))
                .isInstanceOf(BillingSignatureVerificationException.class)
                .hasMessageContaining("Razorpay webhook signature verification failed");
    }

    @Test
    @DisplayName("Razorpay startup configuration validation fails if required price ID is missing")
    void startupValidationFailsOnMissingPriceId() {
        properties.getPrices().remove("business.inr.yearly");

        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Missing required Razorpay price ID for plan=BUSINESS, currency=INR, interval=YEARLY");
    }

    @Test
    @DisplayName("Razorpay startup configuration validation fails if key-id, key-secret, or webhook-secret is missing")
    void startupValidationFailsOnMissingCredentials() {
        properties.setKeyId("");
        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("flowops.billing.razorpay.key-id is required");

        properties.setKeyId(TEST_KEY_ID);
        properties.setKeySecret("");
        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("flowops.billing.razorpay.key-secret is required");

        properties.setKeySecret(TEST_KEY_SECRET);
        properties.setWebhookSecret("");
        assertThatThrownBy(() -> properties.validateConfiguration())
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("flowops.billing.razorpay.webhook-secret is required");
    }

    @Test
    @DisplayName("Razorpay plan change and cancellation operations")
    void planChangeAndCancellation() {
        UUID orgId = UUID.randomUUID();
        SubscriptionResult changed = provider.changePlan(new ChangePlanRequest(
                orgId, "sub_rzp1", "BUSINESS", "YEARLY", "INR"));
        assertThat(changed.planId()).isEqualTo("BUSINESS");
        assertThat(changed.interval()).isEqualTo("YEARLY");
        assertThat(changed.currency()).isEqualTo("INR");

        SubscriptionResult cancelled = provider.cancelSubscription(new CancelSubscriptionRequest(
                orgId, "sub_rzp1", true));
        assertThat(cancelled.cancelAtPeriodEnd()).isTrue();
    }

    @Test
    @DisplayName("Razorpay applyDiscount: applies 30% discount for 12 months with FOUNDING_30 coupon")
    void applyDiscountSuccess() {
        UUID orgId = UUID.randomUUID();
        com.flowops.billing.provider.model.ApplyDiscountRequest req =
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        orgId, "cust_rzp1", "sub_rzp1", "FOUNDING_30", 30, 12);
        com.flowops.billing.provider.model.DiscountResult res = provider.applyDiscount(req);
        assertThat(res).isNotNull();
        assertThat(res.organizationId()).isEqualTo(orgId);
        assertThat(res.couponCode()).isEqualTo("FOUNDING_30");
        assertThat(res.discountPercent()).isEqualTo(30);
        assertThat(res.durationMonths()).isEqualTo(12);
        assertThat(res.status()).isEqualTo("active");
        assertThat(res.discountId()).startsWith("off_");
    }

    @Test
    @DisplayName("Razorpay applyDiscount: rejects invalid discount percent")
    void applyDiscountRejectsInvalidPercent() {
        UUID orgId = UUID.randomUUID();
        com.flowops.billing.provider.model.ApplyDiscountRequest req =
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        orgId, "cust_rzp1", "sub_rzp1", "FOUNDING_30", 150, 12);
        assertThatThrownBy(() -> provider.applyDiscount(req))
                .isInstanceOf(com.flowops.billing.provider.exception.BillingValidationException.class)
                .hasMessageContaining("Invalid discount percent");
    }
}
