import {
  PLANS,
  PRO_PRICES_MINOR,
  SUPPORTED_CURRENCIES,
  type BillingInterval,
  type PlanDefinition,
  type PlanId,
  type SupportedCurrency,
} from "./pricing.config";
import {
  deriveCurrencySymbol,
  getBillingCurrency,
  isSupportedCurrency,
  type BillingCurrencyResolution,
} from "./currency";
import { resolveBillingCountry, type CountryDetectionContext } from "./detector";
import { formatPriceFromMinor, formatZeroPrice } from "./format";
import {
  defaultBillingProvider,
  type BillingProvider,
  type SubscriptionRecord,
} from "./provider";

export interface BillingEnvironmentConfig {
  privateBeta: boolean;
  billingEnabled: boolean;
  defaultCurrency: SupportedCurrency;
  defaultCountry: string;
}

/**
 * Returns the current environment configuration.
 * Fully env-driven with zero hardcoded beta state.
 */
export function getBillingConfig(): BillingEnvironmentConfig {
  const privateBetaEnv = process.env.PRIVATE_BETA;
  // Private beta is active unless explicitly set to "false"
  const privateBeta =
    privateBetaEnv === undefined ||
    privateBetaEnv === "" ||
    privateBetaEnv.toLowerCase() === "true";

  // Billing is disabled unless explicitly set to "true"
  const billingEnabled = process.env.BILLING_ENABLED === "true";

  const defaultCountry = (process.env.DEFAULT_COUNTRY || "US").toUpperCase();
  const defaultCurrencyRaw = (process.env.DEFAULT_CURRENCY || "USD").toUpperCase();
  const defaultCurrency: SupportedCurrency = isSupportedCurrency(defaultCurrencyRaw)
    ? defaultCurrencyRaw
    : "USD";

  return {
    privateBeta,
    billingEnabled,
    defaultCurrency,
    defaultCountry,
  };
}

export interface LocalizedPlanPrice {
  amountMinor: number;
  formatted: string;
  formattedStrikethrough?: string;
  isBetaZero: boolean;
  interval: BillingInterval;
  currency: SupportedCurrency;
  currencySymbol: string;
}

export interface LocalizedPlanView {
  id: PlanId;
  name: string;
  tagline: string;
  betaBadge?: string;
  isCustom: boolean;
  isCurrent: boolean;
  features: string[];
  limits: PlanDefinition["limits"];
  pricing?: LocalizedPlanPrice;
}

export interface WorkspaceUsage {
  workflows: { used: number; limit: number };
  executions: { used: number; limit: number };
  aiGenerations: { used: number; limit: number };
  teamMembers: { used: number; limit: number };
}

export interface BillingState {
  isPrivateBeta: boolean;
  isBillingEnabled: boolean;
  betaNotice: string;
  billingCountry: string;
  billingCurrency: SupportedCurrency;
  currencySymbol: string;
  displayCurrency: SupportedCurrency;
  providerSupported: boolean;
  activePlan: PlanId;
  subscription: SubscriptionRecord | null;
  usage: WorkspaceUsage;
  availableCurrencies: readonly SupportedCurrency[];
}

/**
 * In-memory repository for workspace subscriptions to ensure currency stability
 * across server requests.
 */
class SubscriptionStore {
  private subscriptionsByWorkspace = new Map<string, SubscriptionRecord>();

  get(workspaceId: string): SubscriptionRecord | null {
    return this.subscriptionsByWorkspace.get(workspaceId) ?? null;
  }

  set(record: SubscriptionRecord): void {
    this.subscriptionsByWorkspace.set(record.workspaceId, record);
  }

  /**
   * Transitions a plan (e.g. PRIVATE_BETA -> PRO -> BUSINESS)
   * on the exact same workspace and subscription record without data loss.
   */
  transitionPlan(
    workspaceId: string,
    targetPlan: PlanId,
    interval: BillingInterval = "MONTHLY",
  ): SubscriptionRecord {
    const existing = this.get(workspaceId);
    const now = new Date();
    const periodEnd = new Date(now);
    if (interval === "YEARLY") {
      periodEnd.setFullYear(periodEnd.getFullYear() + 1);
    } else {
      periodEnd.setMonth(periodEnd.getMonth() + 1);
    }

    if (existing) {
      const updated: SubscriptionRecord = {
        ...existing,
        plan: targetPlan,
        billingInterval: interval,
        status: "ACTIVE",
        currentPeriodStart: now.toISOString(),
        currentPeriodEnd: periodEnd.toISOString(),
        updatedAt: now.toISOString(),
      };
      this.set(updated);
      return updated;
    }

    // Default country & currency for new record
    const config = getBillingConfig();
    const resolution = getBillingCurrency(config.defaultCountry);

    const created: SubscriptionRecord = {
      id: `sub_${workspaceId}`,
      workspaceId,
      plan: targetPlan,
      status: "ACTIVE",
      currency: resolution.currency,
      billingCountry: resolution.country,
      billingInterval: interval,
      currentPeriodStart: now.toISOString(),
      currentPeriodEnd: periodEnd.toISOString(),
      createdAt: now.toISOString(),
      updatedAt: now.toISOString(),
    };
    this.set(created);
    return created;
  }
}

export const subscriptionStore = new SubscriptionStore();

/**
 * Mock workspace usage numbers (used if no real execution db is connected).
 */
export const defaultMockUsage: Record<string, Partial<WorkspaceUsage>> = {};

export class BillingService {
  constructor(
    private provider: BillingProvider = defaultBillingProvider,
    private store: SubscriptionStore = subscriptionStore,
  ) {}

  /**
   * Resolves the billing country and currency for a request.
   * Priority:
   * 1. Existing subscription's stored billing country/currency (always wins)
   * 2. Authenticated user's saved country
   * 3. Workspace saved country
   * 4. Provider country
   * 5. Edge/CDN IP headers
   * 6. Browser locale
   * 7. DEFAULT_COUNTRY env
   */
  resolveBillingContext(
    workspaceId?: string | null,
    detectionContext?: CountryDetectionContext,
  ): BillingCurrencyResolution {
    let subCountry: string | null = null;
    let subCurrency: SupportedCurrency | null = null;

    if (workspaceId) {
      const existingSub = this.store.get(workspaceId);
      if (existingSub) {
        subCountry = existingSub.billingCountry;
        subCurrency = existingSub.currency;
      }
    }

    // If an existing subscription exists, its currency & country ALWAYS win
    if (subCountry && subCurrency) {
      return {
        country: subCountry,
        currency: subCurrency,
        symbol: getBillingCurrency(subCountry).symbol,
      };
    }

    const detected = resolveBillingCountry({
      ...detectionContext,
      subscriptionBillingCountry: subCountry ?? detectionContext?.subscriptionBillingCountry,
    });

    return getBillingCurrency(detected.country);
  }

  /**
   * Returns the workspace's active subscription. If none exists, creates a virtual
   * record matching the current environment (PRIVATE_BETA or FREE).
   */
  getOrCreateWorkspaceSubscription(
    workspaceId: string,
    resolvedCurrency: BillingCurrencyResolution,
  ): SubscriptionRecord {
    const existing = this.store.get(workspaceId);
    if (existing) {
      return existing;
    }

    const config = getBillingConfig();
    const defaultPlan: PlanId = config.privateBeta ? "PRIVATE_BETA" : "FREE";
    const now = new Date();
    const periodEnd = new Date(now);
    periodEnd.setFullYear(periodEnd.getFullYear() + 1);

    const newSub: SubscriptionRecord = {
      id: `sub_${workspaceId}`,
      workspaceId,
      plan: defaultPlan,
      status: "ACTIVE",
      currency: resolvedCurrency.currency,
      billingCountry: resolvedCurrency.country,
      billingInterval: "MONTHLY",
      currentPeriodStart: now.toISOString(),
      currentPeriodEnd: periodEnd.toISOString(),
      createdAt: now.toISOString(),
      updatedAt: now.toISOString(),
    };

    this.store.set(newSub);
    return newSub;
  }

  /**
   * Calculates localized plans for UI presentation.
   */
  getLocalizedPlans(
    currency: SupportedCurrency,
    interval: BillingInterval = "MONTHLY",
    currentPlanId: PlanId = "PRIVATE_BETA",
  ): LocalizedPlanView[] {
    const config = getBillingConfig();
    const symbol = deriveCurrencySymbol(currency);

    return (["FREE", "PRIVATE_BETA", "PRO", "BUSINESS"] as PlanId[]).map(
      (planId) => {
        const plan = PLANS[planId];
        const isCurrent = planId === currentPlanId;

        if (plan.isCustom) {
          return {
            id: plan.id,
            name: plan.name,
            tagline: plan.tagline,
            isCustom: true,
            isCurrent,
            features: plan.featureBulletPoints,
            limits: plan.limits,
          };
        }

        if (planId === "FREE") {
          return {
            id: plan.id,
            name: plan.name,
            tagline: plan.tagline,
            isCustom: false,
            isCurrent,
            features: plan.featureBulletPoints,
            limits: plan.limits,
            pricing: {
              amountMinor: 0,
              formatted: formatZeroPrice(currency),
              isBetaZero: false,
              interval,
              currency,
              currencySymbol: symbol,
            },
          };
        }

        if (planId === "PRIVATE_BETA") {
          return {
            id: plan.id,
            name: plan.name,
            tagline: plan.tagline,
            betaBadge: plan.betaBadge,
            isCustom: false,
            isCurrent,
            features: plan.featureBulletPoints,
            limits: plan.limits,
            pricing: {
              amountMinor: 0,
              formatted: formatZeroPrice(currency),
              isBetaZero: true,
              interval,
              currency,
              currencySymbol: symbol,
            },
          };
        }

        // PRO Plan
        const proPriceMinor =
          PRO_PRICES_MINOR[currency] ?? PRO_PRICES_MINOR.USD;
        const amountMinor =
          interval === "YEARLY"
            ? proPriceMinor.yearly
            : proPriceMinor.monthly;
        const fullPriceFormatted = formatPriceFromMinor(amountMinor, currency);

        if (config.privateBeta) {
          // In beta, Pro features are free!
          // Struck-through planned price + localized zero during Private Beta
          return {
            id: plan.id,
            name: plan.name,
            tagline: plan.tagline,
            betaBadge: "Free during Private Beta",
            isCustom: false,
            isCurrent,
            features: plan.featureBulletPoints,
            limits: plan.limits,
            pricing: {
              amountMinor: 0,
              formatted: `${formatZeroPrice(currency)} during Private Beta`,
              formattedStrikethrough: fullPriceFormatted,
              isBetaZero: true,
              interval,
              currency,
              currencySymbol: symbol,
            },
          };
        }

        return {
          id: plan.id,
          name: plan.name,
          tagline: plan.tagline,
          isCustom: false,
          isCurrent,
          features: plan.featureBulletPoints,
          limits: plan.limits,
          pricing: {
            amountMinor,
            formatted: fullPriceFormatted,
            isBetaZero: false,
            interval,
            currency,
            currencySymbol: symbol,
          },
        };
      },
    );
  }

  /**
   * Returns real workspace usage against the plan limits.
   */
  getWorkspaceUsage(
    workspaceId: string,
    planLimits: PlanDefinition["limits"],
    realCounts?: {
      workflowCount?: number;
      executionCount?: number;
      aiGenerationCount?: number;
      memberCount?: number;
    },
  ): WorkspaceUsage {
    return {
      workflows: {
        used: realCounts?.workflowCount ?? 0,
        limit: planLimits.workflows,
      },
      executions: {
        used: realCounts?.executionCount ?? 0,
        limit: planLimits.executionsPerMonth,
      },
      aiGenerations: {
        used: realCounts?.aiGenerationCount ?? 0,
        limit: planLimits.aiGenerationsPerMonth,
      },
      teamMembers: {
        used: realCounts?.memberCount ?? 1,
        limit: planLimits.teamMembers,
      },
    };
  }

  /**
   * Returns full billing state for a workspace and request context.
   */
  getBillingState(
    workspaceId?: string | null,
    displayCurrencyParam?: string | null,
    detectionContext?: CountryDetectionContext,
    realCounts?: {
      workflowCount?: number;
      executionCount?: number;
      aiGenerationCount?: number;
      memberCount?: number;
    },
  ): BillingState {
    const config = getBillingConfig();
    const billingResolution = this.resolveBillingContext(
      workspaceId,
      detectionContext,
    );

    const subscription = workspaceId
      ? this.getOrCreateWorkspaceSubscription(workspaceId, billingResolution)
      : null;

    const activePlan: PlanId =
      subscription?.plan ?? (config.privateBeta ? "PRIVATE_BETA" : "FREE");

    const planDef = PLANS[activePlan];
    const usage = this.getWorkspaceUsage(
      workspaceId ?? "default",
      planDef.limits,
      realCounts,
    );

    const displayCurrency: SupportedCurrency = isSupportedCurrency(
      displayCurrencyParam,
    )
      ? displayCurrencyParam
      : billingResolution.currency;

    const providerSupported = this.provider.isCurrencySupported(
      billingResolution.currency,
    );

    return {
      isPrivateBeta: config.privateBeta,
      isBillingEnabled: config.billingEnabled,
      betaNotice: "Private Beta — Pro features are currently free.",
      billingCountry: billingResolution.country,
      billingCurrency: billingResolution.currency,
      currencySymbol: billingResolution.symbol,
      displayCurrency,
      providerSupported,
      activePlan,
      subscription,
      usage,
      availableCurrencies: SUPPORTED_CURRENCIES,
    };
  }

  /**
   * Checks whether a workspace has exceeded its quota for a given metric.
   */
  enforceLimit(
    workspaceId: string,
    metric: "workflows" | "executionsPerMonth" | "aiGenerationsPerMonth" | "teamMembers",
    currentCount: number,
  ): boolean {
    const sub = this.store.get(workspaceId);
    const planId: PlanId =
      sub?.plan ?? (getBillingConfig().privateBeta ? "PRIVATE_BETA" : "FREE");
    const limit = PLANS[planId].limits[metric];
    return currentCount < limit;
  }
}

export const billingService = new BillingService();
