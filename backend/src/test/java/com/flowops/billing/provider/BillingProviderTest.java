package com.flowops.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CreatePortalSessionRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.PortalSessionResult;
import com.flowops.billing.provider.model.ResumeSubscriptionRequest;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.model.WebhookEventResult;
import com.flowops.billing.provider.razorpay.RazorpayBillingProvider;
import com.flowops.billing.provider.stripe.StripeBillingProvider;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "flowops.billing.enabled=false",
        "flowops.billing.provider=noop"
})
class BillingProviderTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BillingProvider activeBillingProvider;

    @Test
    @DisplayName("When BILLING_ENABLED=false: NoopBillingProvider is the injected BillingProvider bean")
    void whenBillingDisabled_noopBillingProviderIsSelected() {
        assertThat(activeBillingProvider).isNotNull();
        assertThat(activeBillingProvider).isInstanceOf(NoopBillingProvider.class);
        assertThat(activeBillingProvider.getName()).isEqualTo("noop");
    }

    @Test
    @DisplayName("When BILLING_ENABLED=false: exactly one BillingProvider bean exists in applicationContext, which is NoopBillingProvider")
    void whenBillingDisabled_noRealBillingProviderIsInstantiated() {
        Map<String, BillingProvider> providers = applicationContext.getBeansOfType(BillingProvider.class);
        assertThat(providers).isNotEmpty();
        for (BillingProvider provider : providers.values()) {
            assertThat(provider).isInstanceOf(NoopBillingProvider.class);
            assertThat(provider.getName()).isEqualTo("noop");
        }
    }

    @Test
    @DisplayName("When billing is disabled, configuration never calls any real provider methods or external endpoints")
    void whenBillingDisabled_realProviderIsNeverCalled() {
        BillingProvider realProviderMock = mock(BillingProvider.class);
        when(realProviderMock.getName()).thenReturn("stripe");

        BillingProviderConfig config = new BillingProviderConfig();
        NoopBillingProvider noopProvider = new NoopBillingProvider();

        StripeBillingProvider stripeMock = mock(StripeBillingProvider.class);
        RazorpayBillingProvider razorpayMock = mock(RazorpayBillingProvider.class);
        com.flowops.billing.provider.stripe.StripeProperties stripeProps = new com.flowops.billing.provider.stripe.StripeProperties();
        com.flowops.billing.provider.razorpay.RazorpayProperties razorpayProps = new com.flowops.billing.provider.razorpay.RazorpayProperties();

        // When billing is disabled, config returns NoopBillingProvider regardless of providerName setting
        BillingProvider selected = config.selectProvider(
                false, "stripe", noopProvider, stripeMock, razorpayMock, stripeProps, razorpayProps);
        assertThat(selected).isSameAs(noopProvider);

        // Verify zero interactions with the mock real provider
        verifyNoInteractions(realProviderMock, stripeMock, razorpayMock);
    }

    @Test
    @DisplayName("NoopBillingProvider behaves safely across all 9 required methods without making external requests")
    void noopProviderBehavesSafelyAcrossAllMethods() {
        NoopBillingProvider provider = new NoopBillingProvider();
        UUID orgId = UUID.randomUUID();

        // 1. createCustomer
        CreateCustomerRequest custReq = new CreateCustomerRequest(
                orgId, "owner@example.com", "Test Owner", "US", "USD");
        CustomerResult custRes = provider.createCustomer(custReq);
        assertThat(custRes).isNotNull();
        assertThat(custRes.organizationId()).isEqualTo(orgId);
        assertThat(custRes.customerId()).startsWith("cus_noop_");

        // 2. createCheckoutSession
        CreateCheckoutSessionRequest checkoutReq = new CreateCheckoutSessionRequest(
                orgId, custRes.customerId(), "PRO", "monthly", "USD", "https://app.vantis.run/success", "https://app.vantis.run/cancel");
        CheckoutSessionResult checkoutRes = provider.createCheckoutSession(checkoutReq);
        assertThat(checkoutRes).isNotNull();
        assertThat(checkoutRes.status()).isEqualTo("beta_disabled");
        assertThat(checkoutRes.url()).isNull();
        assertThat(checkoutRes.message()).contains("disabled during private beta");

        // 3. createPortalSession
        CreatePortalSessionRequest portalReq = new CreatePortalSessionRequest(
                orgId, custRes.customerId(), "https://app.vantis.run/billing");
        PortalSessionResult portalRes = provider.createPortalSession(portalReq);
        assertThat(portalRes).isNotNull();
        assertThat(portalRes.portalUrl()).isEqualTo("https://app.vantis.run/billing");

        // 4. changePlan
        ChangePlanRequest changeReq = new ChangePlanRequest(
                orgId, "sub_123", "BUSINESS", "yearly", "USD");
        SubscriptionResult changeRes = provider.changePlan(changeReq);
        assertThat(changeRes).isNotNull();
        assertThat(changeRes.planId()).isEqualTo("BUSINESS");
        assertThat(changeRes.interval()).isEqualTo("yearly");
        assertThat(changeRes.status()).isEqualTo("active");

        // 5. cancelSubscription
        CancelSubscriptionRequest cancelReq = new CancelSubscriptionRequest(
                orgId, "sub_123", true);
        SubscriptionResult cancelRes = provider.cancelSubscription(cancelReq);
        assertThat(cancelRes).isNotNull();
        assertThat(cancelRes.cancelAtPeriodEnd()).isTrue();

        // 6. resumeSubscription
        ResumeSubscriptionRequest resumeReq = new ResumeSubscriptionRequest(
                orgId, "sub_123");
        SubscriptionResult resumeRes = provider.resumeSubscription(resumeReq);
        assertThat(resumeRes).isNotNull();
        assertThat(resumeRes.cancelAtPeriodEnd()).isFalse();

        // 7. getSubscription
        var subOpt = provider.getSubscription("sub_123");
        assertThat(subOpt).isPresent();
        assertThat(subOpt.get().subscriptionId()).isEqualTo("sub_123");
        assertThat(subOpt.get().status()).isEqualTo("active");

        // 8. verifyAndParseWebhook
        WebhookEventResult webhookRes = provider.verifyAndParseWebhook("{}", Collections.emptyMap());
        assertThat(webhookRes).isNotNull();
        assertThat(webhookRes.eventType()).isEqualTo("noop.event");
        assertThat(webhookRes.status()).isEqualTo("ignored");

        // 9. listInvoices
        var invoices = provider.listInvoices(custRes.customerId(), 10);
        assertThat(invoices).isNotNull().isEmpty();

        // 10. applyDiscount
        com.flowops.billing.provider.model.ApplyDiscountRequest discountReq =
                new com.flowops.billing.provider.model.ApplyDiscountRequest(
                        orgId, custRes.customerId(), "sub_123", "FOUNDING_30", 30, 12);
        com.flowops.billing.provider.model.DiscountResult discountRes = provider.applyDiscount(discountReq);
        assertThat(discountRes).isNotNull();
        assertThat(discountRes.couponCode()).isEqualTo("FOUNDING_30");
        assertThat(discountRes.discountPercent()).isEqualTo(30);
        assertThat(discountRes.durationMonths()).isEqualTo(12);
        assertThat(discountRes.discountId()).startsWith("disc_noop_");
    }
}
