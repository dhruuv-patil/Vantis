import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  PLANS,
  PRO_PRICES_MINOR,
  SUPPORTED_CURRENCIES,
  type SupportedCurrency,
} from "../billing/pricing.config";
import {
  getBillingCurrency,
  deriveCurrencySymbol,
  isSupportedCurrency,
} from "../lib/billing/currency";
import {
  resolveBillingCountry,
  type CountryDetectionContext,
} from "../lib/billing/detector";
import {
  formatPriceFromMinor,
  formatPriceFromMajor,
  formatZeroPrice,
} from "../lib/billing/format";
import {
  BillingService,
  getBillingConfig,
  subscriptionStore,
} from "../lib/billing/service";
import {
  NoopBillingProvider,
  type CreateCheckoutSessionParams,
} from "../lib/billing/provider";
import { POST as handleCheckoutPost } from "../app/api/billing/checkout/route";
import { POST as handleCancelPost } from "../app/api/billing/cancel/route";
import { NextRequest } from "next/server";

describe("Billing & Pricing Engine", () => {
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  /* -------------------------------------------------------------------------- */
  /* 1. Currency Resolution & Country Mapping                                  */
  /* -------------------------------------------------------------------------- */
  describe("Currency Resolution", () => {
    it("maps single-country currencies correctly", () => {
      expect(getBillingCurrency("IN")).toMatchObject({ country: "IN", currency: "INR" });
      expect(getBillingCurrency("US")).toMatchObject({ country: "US", currency: "USD" });
      expect(getBillingCurrency("GB")).toMatchObject({ country: "GB", currency: "GBP" });
      expect(getBillingCurrency("CA")).toMatchObject({ country: "CA", currency: "CAD" });
      expect(getBillingCurrency("AU")).toMatchObject({ country: "AU", currency: "AUD" });
      expect(getBillingCurrency("SG")).toMatchObject({ country: "SG", currency: "SGD" });
      expect(getBillingCurrency("JP")).toMatchObject({ country: "JP", currency: "JPY" });
    });

    it("maps Eurozone countries to EUR", () => {
      expect(getBillingCurrency("DE")).toMatchObject({ country: "DE", currency: "EUR" });
      expect(getBillingCurrency("FR")).toMatchObject({ country: "FR", currency: "EUR" });
      expect(getBillingCurrency("IT")).toMatchObject({ country: "IT", currency: "EUR" });
      expect(getBillingCurrency("ES")).toMatchObject({ country: "ES", currency: "EUR" });
      expect(getBillingCurrency("NL")).toMatchObject({ country: "NL", currency: "EUR" });
      expect(getBillingCurrency("IE")).toMatchObject({ country: "IE", currency: "EUR" });
    });

    it("falls back to USD for non-euro EU countries and unsupported countries", () => {
      // Non-euro EU countries fall back to USD unless configured
      expect(getBillingCurrency("PL")).toMatchObject({ country: "PL", currency: "USD" });
      expect(getBillingCurrency("SE")).toMatchObject({ country: "SE", currency: "USD" });
      expect(getBillingCurrency("DK")).toMatchObject({ country: "DK", currency: "USD" });

      // Unsupported global countries
      expect(getBillingCurrency("BR")).toMatchObject({ country: "BR", currency: "USD" });
      expect(getBillingCurrency("ZA")).toMatchObject({ country: "ZA", currency: "USD" });
    });

    it("falls back to US/USD when country is missing or invalid", () => {
      expect(getBillingCurrency(null)).toMatchObject({ country: "US", currency: "USD" });
      expect(getBillingCurrency(undefined)).toMatchObject({ country: "US", currency: "USD" });
      expect(getBillingCurrency("")).toMatchObject({ country: "US", currency: "USD" });
      expect(getBillingCurrency("   ")).toMatchObject({ country: "US", currency: "USD" });
    });

    it("derives currency symbols using Intl", () => {
      expect(deriveCurrencySymbol("INR")).toBe("₹");
      expect(deriveCurrencySymbol("USD")).toBe("$");
      expect(deriveCurrencySymbol("GBP")).toBe("£");
      expect(deriveCurrencySymbol("EUR")).toBe("€");
      expect(deriveCurrencySymbol("JPY")).toBe("¥");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 2. Country Detection Priority Order                                       */
  /* -------------------------------------------------------------------------- */
  describe("Country Detection Priority Order", () => {
    it("priority 1: subscription billing country always wins over all other signals", () => {
      const res = resolveBillingCountry({
        subscriptionBillingCountry: "IN",
        userBillingCountry: "US",
        workspaceBillingCountry: "GB",
        providerCustomerCountry: "FR",
        headers: new Headers({ "cf-ipcountry": "DE", "accept-language": "ja-JP" }),
      });

      expect(res).toEqual({ country: "IN", source: "subscription" });
    });

    it("priority 2: user saved country wins over workspace, IP, and locale", () => {
      const res = resolveBillingCountry({
        userBillingCountry: "CA",
        workspaceBillingCountry: "GB",
        providerCustomerCountry: "FR",
        headers: new Headers({ "cf-ipcountry": "DE", "accept-language": "ja-JP" }),
      });

      expect(res).toEqual({ country: "CA", source: "user" });
    });

    it("priority 3: workspace country wins over provider, IP, and locale", () => {
      const res = resolveBillingCountry({
        workspaceBillingCountry: "AU",
        providerCustomerCountry: "FR",
        headers: new Headers({ "cf-ipcountry": "DE", "accept-language": "ja-JP" }),
      });

      expect(res).toEqual({ country: "AU", source: "workspace" });
    });

    it("priority 4: payment provider customer country wins over IP and locale", () => {
      const res = resolveBillingCountry({
        providerCustomerCountry: "SG",
        headers: new Headers({ "cf-ipcountry": "DE", "accept-language": "ja-JP" }),
      });

      expect(res).toEqual({ country: "SG", source: "provider" });
    });

    it("priority 5: edge/CDN geolocation headers win over browser locale", () => {
      const cfRes = resolveBillingCountry({
        headers: new Headers({ "cf-ipcountry": "JP", "accept-language": "en-GB" }),
      });
      expect(cfRes).toEqual({ country: "JP", source: "ip_geolocation" });

      const vercelRes = resolveBillingCountry({
        headers: { "x-vercel-ip-country": "FR", "accept-language": "en-US" },
      });
      expect(vercelRes).toEqual({ country: "FR", source: "ip_geolocation" });
    });

    it("priority 6: browser locale region fallback", () => {
      const res = resolveBillingCountry({
        headers: new Headers({ "accept-language": "en-GB,en;q=0.9" }),
      });
      expect(res).toEqual({ country: "GB", source: "browser_locale" });

      const explicitRes = resolveBillingCountry({
        browserLocale: "de-DE",
      });
      expect(explicitRes).toEqual({ country: "DE", source: "browser_locale" });
    });

    it("priority 7: DEFAULT_COUNTRY env, then US fallback", () => {
      process.env.DEFAULT_COUNTRY = "CA";
      const envRes = resolveBillingCountry({});
      expect(envRes).toEqual({ country: "CA", source: "default_country_env" });

      delete process.env.DEFAULT_COUNTRY;
      const fallbackRes = resolveBillingCountry({});
      expect(fallbackRes).toEqual({ country: "US", source: "fallback_default" });
    });

    it("detection never throws even on malformed inputs", () => {
      expect(() =>
        resolveBillingCountry({
          subscriptionBillingCountry: 12345 as unknown as string,
          headers: null,
          browserLocale: "invalid!@#$",
        }),
      ).not.toThrow();

      const res = resolveBillingCountry({
        subscriptionBillingCountry: "XX", // invalid
        browserLocale: "malformed",
      });
      expect(res.country).toBe("US");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 3. Subscription Currency Stability (Travelling User)                      */
  /* -------------------------------------------------------------------------- */
  describe("Subscription Currency Stability", () => {
    it("existing INR subscription stays INR even if user travels to US", () => {
      const service = new BillingService();
      const workspaceId = "ws-travel-user-123";

      // 1. Initial subscription created in India
      const sub = service.getOrCreateWorkspaceSubscription(workspaceId, {
        country: "IN",
        currency: "INR",
        symbol: "₹",
      });
      expect(sub.currency).toBe("INR");
      expect(sub.billingCountry).toBe("IN");

      // 2. User travels to US (edge IP reports US)
      const travelContext: CountryDetectionContext = {
        headers: new Headers({ "cf-ipcountry": "US" }),
      };

      const resolved = service.resolveBillingContext(workspaceId, travelContext);

      // Currency MUST NOT change
      expect(resolved.currency).toBe("INR");
      expect(resolved.country).toBe("IN");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 4. Formatting with Intl & Zero Decimals                                   */
  /* -------------------------------------------------------------------------- */
  describe("Price Formatting", () => {
    it("formats zero amounts correctly for all currencies", () => {
      expect(formatZeroPrice("INR")).toBe("₹0");
      expect(formatZeroPrice("USD")).toBe("$0");
      expect(formatZeroPrice("GBP")).toBe("£0");
      expect(formatZeroPrice("JPY")).toBe("¥0");
    });

    it("formats major prices without trailing decimals", () => {
      expect(formatPriceFromMajor(1999, "INR")).toBe("₹1,999");
      expect(formatPriceFromMajor(25, "USD")).toBe("$25");
      expect(formatPriceFromMajor(20, "GBP")).toBe("£20");
      expect(formatPriceFromMajor(3500, "JPY")).toBe("¥3,500");
    });

    it("correctly converts minor units to major units", () => {
      // INR: 199900 paise -> ₹1,999
      expect(formatPriceFromMinor(199900, "INR")).toBe("₹1,999");
      // USD: 2500 cents -> $25
      expect(formatPriceFromMinor(2500, "USD")).toBe("$25");
      // JPY: 3500 yen (0 decimals) -> ¥3,500
      expect(formatPriceFromMinor(3500, "JPY")).toBe("¥3,500");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 5. Plans, Limits, and Intervals                                           */
  /* -------------------------------------------------------------------------- */
  describe("Plans and Limits", () => {
    it("provides the exact limits specified in the brief", () => {
      // Free Plan
      expect(PLANS.FREE.limits.workflows).toBe(5);
      expect(PLANS.FREE.limits.executionsPerMonth).toBe(500);
      expect(PLANS.FREE.limits.teamMembers).toBe(1);
      expect(PLANS.FREE.limits.featureFlags.aiWorkflowGeneration).toBe(false);

      // Private Beta Plan
      expect(PLANS.PRIVATE_BETA.limits.workflows).toBe(50);
      expect(PLANS.PRIVATE_BETA.limits.executionsPerMonth).toBe(10000);
      expect(PLANS.PRIVATE_BETA.limits.teamMembers).toBe(5);
      expect(PLANS.PRIVATE_BETA.limits.featureFlags.aiWorkflowGeneration).toBe(true);
      expect(PLANS.PRIVATE_BETA.limits.featureFlags.anomalyDetection).toBe(true);

      // Pro Plan
      expect(PLANS.PRO.limits.workflows).toBe(100);
      expect(PLANS.PRO.limits.executionsPerMonth).toBe(50000);
      expect(PLANS.PRO.limits.teamMembers).toBe(15);

      // Business Plan
      expect(PLANS.BUSINESS.isCustom).toBe(true);
      expect(PLANS.BUSINESS.limits.workflows).toBe(Number.POSITIVE_INFINITY);
    });

    it("calculates localized plan views for monthly vs yearly", () => {
      const service = new BillingService();

      const monthlyPlans = service.getLocalizedPlans("USD", "MONTHLY");
      const yearlyPlans = service.getLocalizedPlans("USD", "YEARLY");

      const proMonthly = monthlyPlans.find((p) => p.id === "PRO");
      const proYearly = yearlyPlans.find((p) => p.id === "PRO");

      expect(proMonthly?.pricing?.formattedStrikethrough).toBe("$25");
      expect(proYearly?.pricing?.formattedStrikethrough).toBe("$250");
    });

    it("preserves workspace record and transitions plans without data loss", () => {
      const wsId = "ws-transition-test";
      const initial = subscriptionStore.transitionPlan(wsId, "PRIVATE_BETA");
      expect(initial.plan).toBe("PRIVATE_BETA");

      const proTransition = subscriptionStore.transitionPlan(wsId, "PRO");
      expect(proTransition.id).toBe(initial.id);
      expect(proTransition.plan).toBe("PRO");

      const bizTransition = subscriptionStore.transitionPlan(wsId, "BUSINESS");
      expect(bizTransition.id).toBe(initial.id);
      expect(bizTransition.plan).toBe("BUSINESS");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 6. Security & Tamper Protection                                           */
  /* -------------------------------------------------------------------------- */
  describe("Security & Tamper Protection", () => {
    it("ignores tampered client payload (plan: PRO, price: 0, currency: 'FAKE')", async () => {
      process.env.BILLING_ENABLED = "true";
      process.env.PRIVATE_BETA = "false";

      const req = new NextRequest("http://localhost:3000/api/billing/checkout", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "cf-ipcountry": "IN",
        },
        body: JSON.stringify({
          workspaceId: "ws-security-test",
          plan: "PRO",
          price: 0, // TAMPER ATTEMPT
          currency: "FAKE", // TAMPER ATTEMPT
        }),
      });

      const res = await handleCheckoutPost(req);
      expect(res.status).toBe(200);
      const json = await res.json();

      // Server resolved INR based on server authority (cf-ipcountry: IN), ignoring FAKE currency and price 0
      expect(json.currency).toBe("INR");
    });

    it("rejects checkout and cancel requests when BILLING_ENABLED=false", async () => {
      process.env.BILLING_ENABLED = "false";
      process.env.PRIVATE_BETA = "true";

      const checkoutReq = new NextRequest("http://localhost:3000/api/billing/checkout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ workspaceId: "ws-test", plan: "PRO" }),
      });

      const checkoutRes = await handleCheckoutPost(checkoutReq);
      expect(checkoutRes.status).toBe(400);
      const checkoutJson = await checkoutRes.json();
      expect(checkoutJson.error).toBe("BILLING_NOT_AVAILABLE");

      const cancelReq = new NextRequest("http://localhost:3000/api/billing/cancel", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ subscriptionId: "sub-123" }),
      });

      const cancelRes = await handleCancelPost(cancelReq);
      expect(cancelRes.status).toBe(400);
      const cancelJson = await cancelRes.json();
      expect(cancelJson.error).toBe("BILLING_NOT_AVAILABLE");
    });
  });

  /* -------------------------------------------------------------------------- */
  /* 7. Edge Cases & Resiliency                                                */
  /* -------------------------------------------------------------------------- */
  describe("Edge Cases", () => {
    it("renders billing state cleanly for anonymous visitors", () => {
      const service = new BillingService();
      const state = service.getBillingState(null, null, {});
      expect(state.billingCountry).toBe("US");
      expect(state.billingCurrency).toBe("USD");
      expect(state.activePlan).toBe("PRIVATE_BETA");
      expect(state.subscription).toBeNull();
    });

    it("respects cosmetic display currency while maintaining billing currency", () => {
      const service = new BillingService();
      // User is detected in India (INR), but selects EUR for display
      const state = service.getBillingState("ws-display-cur", "EUR", {
        userBillingCountry: "IN",
      });

      expect(state.billingCurrency).toBe("INR");
      expect(state.displayCurrency).toBe("EUR");
    });
  });
});
