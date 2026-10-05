import { NextRequest, NextResponse } from "next/server";
import { getBillingConfig } from "@/lib/billing/service";
import { defaultBillingProvider } from "@/lib/billing/provider";

export const dynamic = "force-dynamic";

/**
 * POST /api/billing/cancel
 * Cancels an active subscription.
 * 
 * Security:
 * - Active ONLY when billing is enabled (BILLING_ENABLED=true).
 * - During Private Beta, returns clear "not available" response (400).
 */
export async function POST(request: NextRequest) {
  const config = getBillingConfig();

  if (!config.billingEnabled) {
    return NextResponse.json(
      {
        error: "BILLING_NOT_AVAILABLE",
        message:
          "Billing operations are not available during the private beta.",
        privateBeta: config.privateBeta,
      },
      { status: 400 },
    );
  }

  try {
    const body = await request.json().catch(() => ({}));
    const subscriptionId = body.subscriptionId;

    if (!subscriptionId) {
      return NextResponse.json(
        { error: "VALIDATION_ERROR", message: "subscriptionId is required" },
        { status: 400 },
      );
    }

    const cancelled = await defaultBillingProvider.cancelSubscription(
      subscriptionId,
    );

    return NextResponse.json(cancelled, { status: 200 });
  } catch (error) {
    console.error("Error cancelling subscription:", error);
    return NextResponse.json(
      {
        error: "CANCEL_FAILED",
        message: "Failed to cancel subscription.",
      },
      { status: 500 },
    );
  }
}
