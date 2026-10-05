import { NextRequest, NextResponse } from "next/server";
import { billingService, getBillingConfig } from "@/lib/billing/service";

export const dynamic = "force-dynamic";

/**
 * GET /api/billing
 * Returns current workspace subscription status, resolved billing country & currency,
 * display currency, usage progress, limits, and provider capabilities.
 */
export async function GET(request: NextRequest) {
  try {
    const searchParams = request.nextUrl.searchParams;
    const workspaceId =
      searchParams.get("workspaceId") ||
      request.headers.get("x-workspace-id") ||
      null;
    const displayCurrency = searchParams.get("currency");

    const headersRecord: Record<string, string> = {};
    request.headers.forEach((val, key) => {
      headersRecord[key] = val;
    });

    const billingState = billingService.getBillingState(
      workspaceId,
      displayCurrency,
      {
        headers: request.headers,
        browserLocale: searchParams.get("locale"),
        userBillingCountry: searchParams.get("userCountry"),
        workspaceBillingCountry: searchParams.get("workspaceCountry"),
      },
    );

    return NextResponse.json(billingState, { status: 200 });
  } catch (error) {
    // Pricing/billing queries must NEVER break
    console.error("Error in GET /api/billing:", error);
    const fallback = billingService.getBillingState(null, "USD");
    return NextResponse.json(fallback, { status: 200 });
  }
}
