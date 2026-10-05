import { NextRequest, NextResponse } from "next/server";
import { billingService, getBillingConfig } from "@/lib/billing/service";
import { isSupportedCurrency } from "@/lib/billing/currency";
import type { BillingInterval, SupportedCurrency } from "@/lib/billing/pricing.config";

export const dynamic = "force-dynamic";

/**
 * GET /api/billing/plans
 * Returns available plans with localized regional pricing, feature comparison,
 * and current interval calculations. Never uses live FX conversion.
 */
export async function GET(request: NextRequest) {
  try {
    const searchParams = request.nextUrl.searchParams;
    const workspaceId =
      searchParams.get("workspaceId") ||
      request.headers.get("x-workspace-id") ||
      null;

    const intervalParam = searchParams.get("interval")?.toUpperCase();
    const interval: BillingInterval =
      intervalParam === "YEARLY" ? "YEARLY" : "MONTHLY";

    const billingContext = billingService.resolveBillingContext(workspaceId, {
      headers: request.headers,
      browserLocale: searchParams.get("locale"),
      userBillingCountry: searchParams.get("userCountry"),
      workspaceBillingCountry: searchParams.get("workspaceCountry"),
    });

    const displayCurrencyParam = searchParams.get("currency")?.toUpperCase();
    const targetCurrency: SupportedCurrency = isSupportedCurrency(displayCurrencyParam)
      ? displayCurrencyParam
      : billingContext.currency;

    const config = getBillingConfig();
    const billingState = billingService.getBillingState(
      workspaceId,
      targetCurrency,
      { headers: request.headers },
    );

    const localizedPlans = billingService.getLocalizedPlans(
      targetCurrency,
      interval,
      billingState.activePlan,
    );

    const isDisplayCurrencyDifferent = targetCurrency !== billingContext.currency;

    return NextResponse.json(
      {
        plans: localizedPlans,
        interval,
        displayCurrency: targetCurrency,
        billingCurrency: billingContext.currency,
        billingCountry: billingContext.country,
        currencySymbol: billingContext.symbol,
        isDisplayCurrencyDifferent,
        displayCurrencyNotice: isDisplayCurrencyDifferent
          ? `Display currency (${targetCurrency}) is for reference only. Transactions will be billed in ${billingContext.currency}.`
          : null,
        isPrivateBeta: config.privateBeta,
        isBillingEnabled: config.billingEnabled,
        betaNotice: "Private Beta — Pro features are currently free.",
      },
      { status: 200 },
    );
  } catch (error) {
    console.error("Error in GET /api/billing/plans:", error);
    // Never fail to render pricing plans
    const fallbackPlans = billingService.getLocalizedPlans("USD", "MONTHLY", "PRIVATE_BETA");
    return NextResponse.json(
      {
        plans: fallbackPlans,
        interval: "MONTHLY",
        displayCurrency: "USD",
        billingCurrency: "USD",
        billingCountry: "US",
        currencySymbol: "$",
        isDisplayCurrencyDifferent: false,
        displayCurrencyNotice: null,
        isPrivateBeta: true,
        isBillingEnabled: false,
        betaNotice: "Private Beta — Pro features are currently free.",
      },
      { status: 200 },
    );
  }
}
