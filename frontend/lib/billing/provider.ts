import {
  SUPPORTED_CURRENCIES,
  type BillingInterval,
  type PlanId,
  type SupportedCurrency,
} from "./pricing.config";

export type SubscriptionStatus =
  | "ACTIVE"
  | "TRIALING"
  | "PAST_DUE"
  | "CANCELED"
  | "INCOMPLETE";

export interface SubscriptionRecord {
  id: string;
  workspaceId: string;
  plan: PlanId;
  status: SubscriptionStatus;
  currency: SupportedCurrency;
  billingCountry: string;
  billingInterval: BillingInterval;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  providerCustomerId?: string | null;
  providerSubscriptionId?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCustomerParams {
  workspaceId: string;
  email: string;
  name: string;
  country: string;
  currency: SupportedCurrency;
}

export interface CustomerResult {
  id: string;
  workspaceId: string;
  email: string;
  country: string;
  currency: SupportedCurrency;
}

export interface CreateCheckoutSessionParams {
  workspaceId: string;
  customerId?: string;
  plan: PlanId;
  interval: BillingInterval;
  currency: SupportedCurrency;
  successUrl?: string;
  cancelUrl?: string;
}

export interface CheckoutSessionResult {
  sessionId: string;
  url?: string;
  currency: SupportedCurrency;
  status: "ready" | "beta_disabled";
  message?: string;
}

export interface CreateSubscriptionParams {
  workspaceId: string;
  customerId?: string;
  plan: PlanId;
  interval: BillingInterval;
  currency: SupportedCurrency;
  billingCountry: string;
}

export interface UpdateSubscriptionParams {
  subscriptionId: string;
  plan?: PlanId;
  interval?: BillingInterval;
  billingCountry?: string;
  currency?: SupportedCurrency;
}

/**
 * Pluggable Billing Provider abstraction.
 * Enables zero-touch swapping between Noop, Stripe, Razorpay, etc.
 */
export interface BillingProvider {
  readonly name: string;
  getSupportedCurrencies(): readonly SupportedCurrency[];
  isCurrencySupported(currency: SupportedCurrency): boolean;
  createCustomer(params: CreateCustomerParams): Promise<CustomerResult>;
  createCheckoutSession(
    params: CreateCheckoutSessionParams,
  ): Promise<CheckoutSessionResult>;
  createSubscription(
    params: CreateSubscriptionParams,
  ): Promise<SubscriptionRecord>;
  cancelSubscription(subscriptionId: string): Promise<SubscriptionRecord>;
  updateSubscription(
    params: UpdateSubscriptionParams,
  ): Promise<SubscriptionRecord>;
  getSubscription(
    subscriptionId: string,
  ): Promise<SubscriptionRecord | null>;
}

/**
 * In-memory mock store for Noop provider testing and private beta state.
 */
const mockSubscriptions = new Map<string, SubscriptionRecord>();

/**
 * Noop Billing Provider for Private Beta.
 * Ready to be replaced by StripeBillingProvider or RazorpayBillingProvider once paid billing is enabled.
 */
export class NoopBillingProvider implements BillingProvider {
  readonly name = "noop";

  getSupportedCurrencies(): readonly SupportedCurrency[] {
    return SUPPORTED_CURRENCIES;
  }

  isCurrencySupported(currency: SupportedCurrency): boolean {
    return (SUPPORTED_CURRENCIES as readonly string[]).includes(currency);
  }

  async createCustomer(params: CreateCustomerParams): Promise<CustomerResult> {
    return {
      id: `cus_noop_${params.workspaceId}`,
      workspaceId: params.workspaceId,
      email: params.email,
      country: params.country,
      currency: params.currency,
    };
  }

  async createCheckoutSession(
    params: CreateCheckoutSessionParams,
  ): Promise<CheckoutSessionResult> {
    // In beta, charging is disabled
    return {
      sessionId: `cs_noop_${Date.now()}`,
      currency: params.currency,
      status: "beta_disabled",
      message: "Billing will be available after the private beta.",
    };
  }

  async createSubscription(
    params: CreateSubscriptionParams,
  ): Promise<SubscriptionRecord> {
    const now = new Date();
    const periodEnd = new Date(now);
    if (params.interval === "YEARLY") {
      periodEnd.setFullYear(periodEnd.getFullYear() + 1);
    } else {
      periodEnd.setMonth(periodEnd.getMonth() + 1);
    }

    const sub: SubscriptionRecord = {
      id: `sub_noop_${params.workspaceId}`,
      workspaceId: params.workspaceId,
      plan: params.plan,
      status: "ACTIVE",
      currency: params.currency,
      billingCountry: params.billingCountry,
      billingInterval: params.interval,
      currentPeriodStart: now.toISOString(),
      currentPeriodEnd: periodEnd.toISOString(),
      providerCustomerId: params.customerId ?? `cus_noop_${params.workspaceId}`,
      providerSubscriptionId: `sub_prov_${Date.now()}`,
      createdAt: now.toISOString(),
      updatedAt: now.toISOString(),
    };

    mockSubscriptions.set(sub.id, sub);
    mockSubscriptions.set(`ws_${params.workspaceId}`, sub);
    return sub;
  }

  async cancelSubscription(subscriptionId: string): Promise<SubscriptionRecord> {
    const existing = mockSubscriptions.get(subscriptionId);
    if (!existing) {
      throw new Error(`Subscription ${subscriptionId} not found`);
    }

    const updated: SubscriptionRecord = {
      ...existing,
      status: "CANCELED",
      updatedAt: new Date().toISOString(),
    };

    mockSubscriptions.set(subscriptionId, updated);
    return updated;
  }

  async updateSubscription(
    params: UpdateSubscriptionParams,
  ): Promise<SubscriptionRecord> {
    const existing = mockSubscriptions.get(params.subscriptionId);
    if (!existing) {
      throw new Error(`Subscription ${params.subscriptionId} not found`);
    }

    const updated: SubscriptionRecord = {
      ...existing,
      plan: params.plan ?? existing.plan,
      billingInterval: params.interval ?? existing.billingInterval,
      billingCountry: params.billingCountry ?? existing.billingCountry,
      currency: params.currency ?? existing.currency,
      updatedAt: new Date().toISOString(),
    };

    mockSubscriptions.set(params.subscriptionId, updated);
    return updated;
  }

  async getSubscription(
    subscriptionId: string,
  ): Promise<SubscriptionRecord | null> {
    return mockSubscriptions.get(subscriptionId) ?? null;
  }
}

/** Default singleton instance */
export const defaultBillingProvider = new NoopBillingProvider();
