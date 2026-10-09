package com.flowops.billing.provider;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * No-op billing provider used when BILLING_ENABLED=false or in private beta.
 * Does not make external network requests and behaves safely for all calls.
 */
@Component("noopBillingProvider")
public class NoopBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopBillingProvider.class);

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public CustomerResult createCustomer(CreateCustomerRequest request) {
        log.info("NoopBillingProvider: createCustomer called for org={}", request.organizationId());
        return new CustomerResult(
                "cus_noop_" + request.organizationId(),
                request.organizationId(),
                request.email(),
                request.name(),
                request.country(),
                request.currency());
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionRequest request) {
        log.info("NoopBillingProvider: createCheckoutSession called for org={} plan={}",
                request.organizationId(), request.planId());
        return new CheckoutSessionResult(
                "cs_noop_" + UUID.randomUUID(),
                null,
                request.currency(),
                "beta_disabled",
                "Paid billing is currently disabled during private beta.");
    }

    @Override
    public PortalSessionResult createPortalSession(CreatePortalSessionRequest request) {
        log.info("NoopBillingProvider: createPortalSession called for org={}", request.organizationId());
        return new PortalSessionResult("https://app.vantis.run/billing");
    }

    @Override
    public SubscriptionResult changePlan(ChangePlanRequest request) {
        log.info("NoopBillingProvider: changePlan called for org={} newPlan={}",
                request.organizationId(), request.planId());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SubscriptionResult(
                request.subscriptionId() != null ? request.subscriptionId() : "sub_noop_" + request.organizationId(),
                "cus_noop_" + request.organizationId(),
                request.planId(),
                "active",
                request.interval() != null ? request.interval() : "monthly",
                request.currency() != null ? request.currency() : "USD",
                now,
                now.plus(30, ChronoUnit.DAYS),
                false);
    }

    @Override
    public SubscriptionResult cancelSubscription(CancelSubscriptionRequest request) {
        log.info("NoopBillingProvider: cancelSubscription called for org={} subId={} atPeriodEnd={}",
                request.organizationId(), request.subscriptionId(), request.atPeriodEnd());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SubscriptionResult(
                request.subscriptionId() != null ? request.subscriptionId() : "sub_noop_" + request.organizationId(),
                "cus_noop_" + request.organizationId(),
                "FREE",
                request.atPeriodEnd() ? "active" : "canceled",
                "monthly",
                "USD",
                now,
                now.plus(30, ChronoUnit.DAYS),
                request.atPeriodEnd());
    }

    @Override
    public SubscriptionResult resumeSubscription(ResumeSubscriptionRequest request) {
        log.info("NoopBillingProvider: resumeSubscription called for org={} subId={}",
                request.organizationId(), request.subscriptionId());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SubscriptionResult(
                request.subscriptionId() != null ? request.subscriptionId() : "sub_noop_" + request.organizationId(),
                "cus_noop_" + request.organizationId(),
                "PRO",
                "active",
                "monthly",
                "USD",
                now,
                now.plus(30, ChronoUnit.DAYS),
                false);
    }

    @Override
    public Optional<SubscriptionResult> getSubscription(String subscriptionId) {
        log.info("NoopBillingProvider: getSubscription called for subId={}", subscriptionId);
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return Optional.of(new SubscriptionResult(
                subscriptionId,
                "cus_noop",
                "PRIVATE_BETA",
                "active",
                "monthly",
                "USD",
                now,
                now.plus(30, ChronoUnit.DAYS),
                false));
    }

    @Override
    public WebhookEventResult verifyAndParseWebhook(String payload, Map<String, String> headers) {
        log.info("NoopBillingProvider: verifyAndParseWebhook called");
        return new WebhookEventResult(
                "evt_noop_" + UUID.randomUUID(),
                "noop.event",
                Instant.now(),
                null,
                null,
                "ignored",
                null,
                Collections.emptyMap());
    }

    @Override
    public List<InvoiceResult> listInvoices(String customerId, int limit) {
        log.info("NoopBillingProvider: listInvoices called for customerId={}", customerId);
        return Collections.emptyList();
    }

    @Override
    public com.flowops.billing.provider.model.DiscountResult applyDiscount(com.flowops.billing.provider.model.ApplyDiscountRequest request) {
        log.info("NoopBillingProvider: applyDiscount called for org={} coupon={} discountPct={}% durationMonths={}",
                request.organizationId(), request.couponCode(), request.discountPercent(), request.durationMonths());
        return new com.flowops.billing.provider.model.DiscountResult(
                "disc_noop_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                request.organizationId(),
                request.couponCode(),
                request.discountPercent(),
                request.durationMonths(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "active");
    }
}
