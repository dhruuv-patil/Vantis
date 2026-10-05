import { NextRequest, NextResponse } from "next/server";
import { getBillingCurrency } from "@/lib/billing/currency";
import { resolveBillingCountry } from "@/lib/billing/detector";
import { SUPPORTED_CURRENCIES } from "@/lib/billing/pricing.config";

export const dynamic = "force-dynamic";

/**
 * GET /api/billing/currency
 * Resolves the billing country, currency, and symbol based on request headers,
 * query parameters, or explicit country code.
 */
export async function GET(request: NextRequest) {
  try {
    const searchParams = request.nextUrl.searchParams;
    const countryParam = searchParams.get("country");

    let country: string;
    let detectionSource: string;

    if (countryParam && countryParam.trim().length > 0) {
      country = countryParam.trim().toUpperCase();
      detectionSource = "query_parameter";
    } else {
      const detected = resolveBillingCountry({
        headers: request.headers,
        browserLocale: searchParams.get("locale"),
        userBillingCountry: searchParams.get("userCountry"),
        workspaceBillingCountry: searchParams.get("workspaceCountry"),
        subscriptionBillingCountry: searchParams.get("subscriptionCountry"),
      });
      country = detected.country;
      detectionSource = detected.source;
    }

    const resolution = getBillingCurrency(country);

    return NextResponse.json(
      {
        ...resolution,
        detectionSource,
        supportedCurrencies: SUPPORTED_CURRENCIES,
      },
      { status: 200 },
    );
  } catch (error) {
    console.error("Error in GET /api/billing/currency:", error);
    const fallback = getBillingCurrency("US");
    return NextResponse.json(
      {
        ...fallback,
        detectionSource: "fallback_default",
        supportedCurrencies: SUPPORTED_CURRENCIES,
      },
      { status: 200 },
    );
  }
}
