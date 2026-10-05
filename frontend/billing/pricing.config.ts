/**
 * Centralized Pricing & Subscription Configuration (Single Source of Truth).
 * 
 * All plans, feature flags, usage limits, and intentionally configured regional prices
 * are defined here. Prices are stored in integer minor units (e.g. cents, paise, yen)
 * to eliminate floating-point precision errors.
 * 
 * Never use live FX conversion — regional prices are explicitly configured.
 */

export type PlanId = "FREE" | "PRIVATE_BETA" | "PRO" | "BUSINESS";

export type BillingInterval = "MONTHLY" | "YEARLY";

export type SupportedCurrency =
  | "INR"
  | "USD"
  | "GBP"
  | "EUR"
  | "CAD"
  | "AUD"
  | "SGD"
  | "JPY";

export const SUPPORTED_CURRENCIES: readonly SupportedCurrency[] = [
  "INR",
  "USD",
  "GBP",
  "EUR",
  "CAD",
  "AUD",
  "SGD",
  "JPY",
] as const;

export interface CurrencyMeta {
  code: SupportedCurrency;
  decimals: number;
  name: string;
  defaultLocale: string;
}

export const CURRENCY_METADATA: Record<SupportedCurrency, CurrencyMeta> = {
  INR: { code: "INR", decimals: 2, name: "Indian Rupee", defaultLocale: "en-IN" },
  USD: { code: "USD", decimals: 2, name: "US Dollar", defaultLocale: "en-US" },
  GBP: { code: "GBP", decimals: 2, name: "British Pound", defaultLocale: "en-GB" },
  EUR: { code: "EUR", decimals: 2, name: "Euro", defaultLocale: "de-DE" },
  CAD: { code: "CAD", decimals: 2, name: "Canadian Dollar", defaultLocale: "en-CA" },
  AUD: { code: "AUD", decimals: 2, name: "Australian Dollar", defaultLocale: "en-AU" },
  SGD: { code: "SGD", decimals: 2, name: "Singapore Dollar", defaultLocale: "en-SG" },
  JPY: { code: "JPY", decimals: 0, name: "Japanese Yen", defaultLocale: "ja-JP" },
};

export interface PlanLimits {
  workflows: number;
  executionsPerMonth: number;
  aiGenerationsPerMonth: number;
  teamMembers: number;
  featureFlags: {
    basicMonitoring: boolean;
    basicReliabilityDetection: boolean;
    aiWorkflowGeneration: boolean;
    aiInvestigations: boolean;
    reliabilityMonitoring: boolean;
    anomalyDetection: boolean;
    workflowVersioning: boolean;
    allIntegrations: boolean;
    priorityBetaSupport: boolean;
    customIntegrations: boolean;
    dedicatedAccountManager: boolean;
    customSla: boolean;
  };
}

export interface PlanPriceMinor {
  monthly: number;
  yearly: number;
}

export interface PlanDefinition {
  id: PlanId;
  name: string;
  tagline: string;
  betaBadge?: string;
  limits: PlanLimits;
  featureBulletPoints: string[];
  /**
   * Intentionally configured regional prices for paid plans.
   * Defined in minor units: INR (paise), USD (cents), JPY (yen, 0-decimal).
   */
  prices?: Record<SupportedCurrency, PlanPriceMinor>;
  isCustom?: boolean;
}

/**
 * Starting PRO prices (in major units):
 * INR: 1999 / 19990
 * USD: 25 / 250
 * GBP: 20 / 200
 * EUR: 23 / 230
 * CAD: 35 / 350
 * AUD: 39 / 390
 * SGD: 34 / 340
 * JPY: 3500 / 35000 (zero-decimal currency)
 */
export const PRO_PRICES_MINOR: Record<SupportedCurrency, PlanPriceMinor> = {
  INR: { monthly: 199900, yearly: 1999000 },
  USD: { monthly: 2500, yearly: 25000 },
  GBP: { monthly: 2000, yearly: 20000 },
  EUR: { monthly: 2300, yearly: 23000 },
  CAD: { monthly: 3500, yearly: 35000 },
  AUD: { monthly: 3900, yearly: 39000 },
  SGD: { monthly: 3400, yearly: 34000 },
  JPY: { monthly: 3500, yearly: 35000 },
};

export const PLANS: Record<PlanId, PlanDefinition> = {
  FREE: {
    id: "FREE",
    name: "Free",
    tagline: "Core building blocks for individual developers.",
    limits: {
      workflows: 5,
      executionsPerMonth: 500,
      aiGenerationsPerMonth: 10,
      teamMembers: 1,
      featureFlags: {
        basicMonitoring: true,
        basicReliabilityDetection: true,
        aiWorkflowGeneration: false,
        aiInvestigations: false,
        reliabilityMonitoring: false,
        anomalyDetection: false,
        workflowVersioning: false,
        allIntegrations: false,
        priorityBetaSupport: false,
        customIntegrations: false,
        dedicatedAccountManager: false,
        customSla: false,
      },
    },
    featureBulletPoints: [
      "5 active workflows",
      "500 executions/month",
      "Basic reliability detection",
      "Limited AI generations",
      "1 team member",
    ],
  },

  PRIVATE_BETA: {
    id: "PRIVATE_BETA",
    name: "Private Beta",
    tagline: "Full access to Pro features without cost during our preview period.",
    betaBadge: "Free during Private Beta",
    limits: {
      workflows: 50,
      executionsPerMonth: 10000,
      aiGenerationsPerMonth: 100,
      teamMembers: 5,
      featureFlags: {
        basicMonitoring: true,
        basicReliabilityDetection: true,
        aiWorkflowGeneration: true,
        aiInvestigations: true,
        reliabilityMonitoring: true,
        anomalyDetection: true,
        workflowVersioning: true,
        allIntegrations: true,
        priorityBetaSupport: true,
        customIntegrations: false,
        dedicatedAccountManager: false,
        customSla: false,
      },
    },
    featureBulletPoints: [
      "50 active workflows",
      "10,000 executions/month",
      "AI workflow generation & investigations",
      "Reliability monitoring & anomaly detection",
      "Workflow versioning & history",
      "All native integrations",
      "5 team members",
      "Priority beta support",
    ],
  },

  PRO: {
    id: "PRO",
    name: "Pro",
    tagline: "Production-grade automation with advanced reliability and AI capabilities.",
    limits: {
      workflows: 100,
      executionsPerMonth: 50000,
      aiGenerationsPerMonth: 500,
      teamMembers: 15,
      featureFlags: {
        basicMonitoring: true,
        basicReliabilityDetection: true,
        aiWorkflowGeneration: true,
        aiInvestigations: true,
        reliabilityMonitoring: true,
        anomalyDetection: true,
        workflowVersioning: true,
        allIntegrations: true,
        priorityBetaSupport: true,
        customIntegrations: false,
        dedicatedAccountManager: false,
        customSla: false,
      },
    },
    prices: PRO_PRICES_MINOR,
    featureBulletPoints: [
      "100 active workflows",
      "50,000 executions/month",
      "Full AI suite (generation, optimization, triage)",
      "High-frequency anomaly detection & auto-remediation",
      "Unlimited version rollback",
      "All integrations & webhook ingress",
      "15 team members",
      "Priority production SLA",
    ],
  },

  BUSINESS: {
    id: "BUSINESS",
    name: "Business",
    tagline: "Dedicated infrastructure, security governance, and bespoke volume.",
    isCustom: true,
    limits: {
      workflows: Number.POSITIVE_INFINITY,
      executionsPerMonth: Number.POSITIVE_INFINITY,
      aiGenerationsPerMonth: Number.POSITIVE_INFINITY,
      teamMembers: Number.POSITIVE_INFINITY,
      featureFlags: {
        basicMonitoring: true,
        basicReliabilityDetection: true,
        aiWorkflowGeneration: true,
        aiInvestigations: true,
        reliabilityMonitoring: true,
        anomalyDetection: true,
        workflowVersioning: true,
        allIntegrations: true,
        priorityBetaSupport: true,
        customIntegrations: true,
        dedicatedAccountManager: true,
        customSla: true,
      },
    },
    featureBulletPoints: [
      "Unlimited workflows & executions",
      "Custom integrations & dedicated VPC agent tunnels",
      "Enterprise SLA & 99.99% uptime guarantee",
      "Unlimited team members & role-based governance",
      "Dedicated account manager & solutions engineering",
    ],
  },
};

/**
 * Eurozone countries that use EUR.
 * Explicit list per requirements. Non-euro EU countries fall back to USD unless configured.
 */
export const EUROZONE_COUNTRIES: ReadonlySet<string> = new Set([
  "AT", // Austria
  "BE", // Belgium
  "CY", // Cyprus
  "EE", // Estonia
  "FI", // Finland
  "FR", // France
  "DE", // Germany
  "GR", // Greece
  "IE", // Ireland
  "IT", // Italy
  "LV", // Latvia
  "LT", // Lithuania
  "LU", // Luxembourg
  "MT", // Malta
  "NL", // Netherlands
  "PT", // Portugal
  "SK", // Slovakia
  "SI", // Slovenia
  "ES", // Spain
  "HR", // Croatia
]);

/**
 * Country code to currency code mapping for supported single-country currencies.
 */
export const COUNTRY_TO_CURRENCY_MAP: Record<string, SupportedCurrency> = {
  IN: "INR",
  US: "USD",
  GB: "GBP",
  CA: "CAD",
  AU: "AUD",
  SG: "SGD",
  JP: "JPY",
};
