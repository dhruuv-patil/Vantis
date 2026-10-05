import { NextRequest, NextResponse } from "next/server";
import { billingService, getBillingConfig } from "@/lib/billing/service";
import { defaultBillingProvider } from "@/lib/billing/provider";
import { PLANS, type BillingInterval, type PlanId } from "@/lib/billing/pricing.config";

export const dynamic = "force-dynamic";

/**
 * POST /api/billing/checkout
 * Initializes a checkout session for paid subscriptions.
 * 
 * Security:
 * - Active ONLY when billing is enabled (BILLING_ENABLED=true).
 * - During Private Beta, returns clear "not available" response (400).
 * - Backend is the sole authority: client-supplied price or currency is completely ignored.
 * - Plan, currency, and minor-unit amounts are computed strictly server-side.
 */
export async function POST(request: NextRequest) {
  const config = getBillingConfig();

  if (!config.billingEnabled) {
    return NextResponse.json(
      {
        error: "BILLING_NOT_AVAILABLE",
        message:
          "Billing will be available after the private beta. Pro features are currently free.",
        privateBeta: config.privateBeta,
      },
      { status: 400 },
    );
  }

  try {
    const body = await request.json().catch(() => ({}));
    const workspaceId =
      body.workspaceId || request.headers.get("x-workspace-id");

    if (!workspaceId) {
      return NextResponse.json(
        { error: "VALIDATION_ERROR", message: "workspaceId is required" },
        { status: 400 },
      );
    }

    // Target plan validation: Only "PRO" can be checked out directly. Free is default, Business is contact sales.
    const requestedPlan: PlanId =
      body.plan === "PRO" ? "PRO" : "PRO";

    const requestedInterval: BillingInterval =
      body.interval === "YEARLY" ? "YEARLY" : "MONTHLY";

    // Authority: resolve billing country & currency server-side. Client-supplied price/currency is ignored!
    const billingContext = billingService.resolveBillingContext(workspaceId, {
      headers: request.headers,
    });

    const session = await defaultBillingProvider.createCheckoutSession({
      workspaceId,
      customerId: body.customerId,
      plan: requestedPlan,
      interval: requestedInterval,
      currency: billingContext.currency, // SERVER-RESOLVED CURRENCY ONLY
      successUrl: body.successUrl || "/billing?checkout=success",
      cancelUrl: body.cancelUrl || "/billing?checkout=cancelled",
    });

    return NextResponse.json(session, { status: 200 });
  } catch (error) {
    console.error("Error creating checkout session:", error);
    return NextResponse.json(
      {
        error: "CHECKOUT_FAILED",
        message: "Failed to create checkout session. Please try again later.",
      },
      { status: 500 },
    );
  }
}
