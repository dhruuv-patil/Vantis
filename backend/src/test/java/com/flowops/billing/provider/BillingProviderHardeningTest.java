package com.flowops.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowops.billing.provider.exception.BillingConfigurationException;
import com.flowops.billing.provider.razorpay.RazorpayBillingProvider;
import com.flowops.billing.provider.razorpay.RazorpayProperties;
import com.flowops.billing.provider.stripe.StripeBillingProvider;
import com.flowops.billing.provider.stripe.StripeProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BillingProviderHardeningTest {

    private BillingProviderConfig config;
    private NoopBillingProvider noopProvider;
    private StripeBillingProvider stripeProviderMock;
    private RazorpayBillingProvider razorpayProviderMock;
    private StripeProperties validStripeProps;
    private RazorpayProperties validRazorpayProps;

    @BeforeEach
    void setUp() {
        config = new BillingProviderConfig();
        noopProvider = new NoopBillingProvider();
        stripeProviderMock = mock(StripeBillingProvider.class);
        razorpayProviderMock = mock(RazorpayBillingProvider.class);

        validStripeProps = new StripeProperties();
        validStripeProps.setSecretKey("sk_test_123");
        validStripeProps.setWebhookSecret("whsec_test_123");
        Map<String, String> stripePrices = new HashMap<>();
        for (String p : new String[]{"pro", "business"}) {
            for (String c : new String[]{"usd", "gbp", "eur", "cad", "aud", "sgd", "jpy"}) {
                for (String i : new String[]{"monthly", "yearly"}) {
                    stripePrices.put(p + "." + c + "." + i, "price_" + p + "_" + c + "_" + i);
                }
            }
        }
        validStripeProps.setPrices(stripePrices);

        validRazorpayProps = new RazorpayProperties();
        validRazorpayProps.setKeyId("rzp_key_123");
        validRazorpayProps.setKeySecret("rzp_sec_123");
        validRazorpayProps.setWebhookSecret("rzp_whsec_123");
        Map<String, String> rzpPrices = new HashMap<>();
        rzpPrices.put("pro.inr.monthly", "plan_pro_m");
        rzpPrices.put("pro.inr.yearly", "plan_pro_y");
        rzpPrices.put("business.inr.monthly", "plan_biz_m");
        rzpPrices.put("business.inr.yearly", "plan_biz_y");
        validRazorpayProps.setPrices(rzpPrices);
    }

    @Test
    @DisplayName("When BILLING_ENABLED=false, always selects NoopBillingProvider regardless of provider setting")
    void billingDisabledAlwaysSelectsNoop() {
        BillingProvider provider1 = config.selectProvider(
                false, "stripe", noopProvider, stripeProviderMock, razorpayProviderMock,
                new StripeProperties(), new RazorpayProperties());
        assertThat(provider1).isSameAs(noopProvider);

        BillingProvider provider2 = config.selectProvider(
                false, "razorpay", noopProvider, stripeProviderMock, razorpayProviderMock,
                new StripeProperties(), new RazorpayProperties());
        assertThat(provider2).isSameAs(noopProvider);

        BillingProvider provider3 = config.selectProvider(
                false, "unknown_random_provider", noopProvider, stripeProviderMock, razorpayProviderMock,
                new StripeProperties(), new RazorpayProperties());
        assertThat(provider3).isSameAs(noopProvider);

        verifyNoInteractions(stripeProviderMock, razorpayProviderMock);
    }

    @Test
    @DisplayName("When BILLING_ENABLED=true and provider=stripe, requires valid Stripe configuration")
    void billingEnabledStripeSelection() {
        BillingProvider selected = config.selectProvider(
                true, "stripe", noopProvider, stripeProviderMock, razorpayProviderMock,
                validStripeProps, validRazorpayProps);
        assertThat(selected).isSameAs(stripeProviderMock);

        // With invalid/missing Stripe properties:
        StripeProperties brokenStripeProps = new StripeProperties(); // missing secrets & prices
        assertThatThrownBy(() -> config.selectProvider(
                true, "stripe", noopProvider, stripeProviderMock, razorpayProviderMock,
                brokenStripeProps, validRazorpayProps))
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Stripe billing configuration validation failed");
    }

    @Test
    @DisplayName("When BILLING_ENABLED=true and provider=razorpay, requires valid Razorpay configuration")
    void billingEnabledRazorpaySelection() {
        BillingProvider selected = config.selectProvider(
                true, "razorpay", noopProvider, stripeProviderMock, razorpayProviderMock,
                validStripeProps, validRazorpayProps);
        assertThat(selected).isSameAs(razorpayProviderMock);

        // With invalid/missing Razorpay properties:
        RazorpayProperties brokenRazorpayProps = new RazorpayProperties();
        assertThatThrownBy(() -> config.selectProvider(
                true, "razorpay", noopProvider, stripeProviderMock, razorpayProviderMock,
                validStripeProps, brokenRazorpayProps))
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Razorpay billing configuration validation failed");
    }

    @Test
    @DisplayName("When BILLING_ENABLED=true and provider=noop, explicitly selects NoopBillingProvider")
    void billingEnabledNoopExplicit() {
        BillingProvider selected = config.selectProvider(
                true, "noop", noopProvider, stripeProviderMock, razorpayProviderMock,
                new StripeProperties(), new RazorpayProperties());
        assertThat(selected).isSameAs(noopProvider);
    }

    @Test
    @DisplayName("When BILLING_ENABLED=true and unknown provider configured, FAILS FAST rather than falling back to Noop")
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> config.selectProvider(
                true, "paypal", noopProvider, stripeProviderMock, razorpayProviderMock,
                validStripeProps, validRazorpayProps))
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Unknown billing provider 'paypal' configured while billing is enabled");

        assertThatThrownBy(() -> config.selectProvider(
                true, "invalid_provider", noopProvider, stripeProviderMock, razorpayProviderMock,
                validStripeProps, validRazorpayProps))
                .isInstanceOf(BillingConfigurationException.class)
                .hasMessageContaining("Unknown billing provider 'invalid_provider' configured while billing is enabled");
    }
}
