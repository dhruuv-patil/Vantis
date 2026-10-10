package com.flowops.billing.provider;

import com.flowops.billing.provider.model.CancelSubscriptionRequest;
import com.flowops.billing.provider.model.ChangePlanRequest;
import com.flowops.billing.provider.model.CheckoutSessionResult;
import com.flowops.billing.provider.model.CreateCheckoutSessionRequest;
import com.flowops.billing.provider.model.CreateCustomerRequest;
import com.flowops.billing.provider.model.CreatePortalSessionRequest;
import com.flowops.billing.provider.model.CustomerResult;
import com.flowops.billing.provider.model.DiscountResult;
import com.flowops.billing.provider.model.InvoiceResult;
import com.flowops.billing.provider.model.PortalSessionResult;
import com.flowops.billing.provider.model.ResumeSubscriptionRequest;
import com.flowops.billing.provider.model.SubscriptionResult;
import com.flowops.billing.provider.model.WebhookEventResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pluggable Billing Provider abstraction for Vantis SaaS billing.
 * Allows zero-touch swapping between NoopBillingProvider (during private beta or when
 * billing is disabled) and real providers like Stripe or Razorpay.
 */
public interface BillingProvider {

    /** Identifier for the provider (e.g. "noop", "stripe", "razorpay"). */
    String getName();

    /** Creates or registers a customer identity with the billing provider. */
    CustomerResult createCustomer(CreateCustomerRequest request);

    /** Creates a hosted checkout session for subscription purchasing. */
    CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionRequest request);

    /** Creates a self-service customer portal session for subscription management. */
    PortalSessionResult createPortalSession(CreatePortalSessionRequest request);

    /** Upgrades or downgrades an existing subscription's plan or billing interval. */
    SubscriptionResult changePlan(ChangePlanRequest request);

    /** Cancels an active subscription immediately or at current period end. */
    SubscriptionResult cancelSubscription(CancelSubscriptionRequest request);

    /** Resumes a subscription scheduled for cancellation at period end. */
    SubscriptionResult resumeSubscription(ResumeSubscriptionRequest request);

    /** Fetches the latest subscription record from the billing provider. */
    Optional<SubscriptionResult> getSubscription(String subscriptionId);

    /** Verifies webhook request signature and parses into structured event payload. */
    WebhookEventResult verifyAndParseWebhook(String payload, Map<String, String> headers);

    /** Lists recent invoices for a customer. */
    List<InvoiceResult> listInvoices(String customerId, int limit);

    /** Applies a discount or promotion code to a customer or subscription. */
    DiscountResult applyDiscount(com.flowops.billing.provider.model.ApplyDiscountRequest request);
}
