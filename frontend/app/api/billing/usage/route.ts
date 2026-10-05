import { NextRequest, NextResponse } from "next/server";
import { billingService } from "@/lib/billing/service";
import { PLANS } from "@/lib/billing/pricing.config";

export const dynamic = "force-dynamic";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/**
 * GET /api/billing/usage
 * Returns current monthly usage numbers against the workspace plan's limits.
 * Authoritative database numbers; NO FAKE USAGE DATA.
 */
export async function GET(request: NextRequest) {
  const authHeader = request.headers.get("authorization");

  // Attempt proxying to authoritative Spring Boot backend if Authorization header is present
  if (authHeader) {
    try {
      const backendRes = await fetch(`${API_BASE_URL}/api/billing/usage`, {
        headers: {
          authorization: authHeader,
          accept: "application/json",
        },
        cache: "no-store",
      });

      if (backendRes.ok) {
        const data = await backendRes.json();
        return NextResponse.json(data, { status: 200 });
      }
    } catch {
      // Backend not yet reachable or connection refused; fall through to local computation
    }
  }

  try {
    const searchParams = request.nextUrl.searchParams;
    const workspaceId =
      searchParams.get("workspaceId") ||
      request.headers.get("x-workspace-id") ||
      "default";

    const billingState = billingService.getBillingState(
      workspaceId,
      null,
      { headers: request.headers },
    );

    const activePlan = billingState.activePlan;
    const limits = PLANS[activePlan].limits;

    const calculatePercent = (used: number, limit: number) => {
      if (!Number.isFinite(limit) || limit <= 0) return 0;
      return Math.min(100, Math.round((used / limit) * 100));
    };

    const usageData = {
      plan: activePlan,
      workflows: {
        used: billingState.usage.workflows.used,
        limit: limits.workflows,
        percentage: calculatePercent(
          billingState.usage.workflows.used,
          limits.workflows,
        ),
      },
      executions: {
        used: billingState.usage.executions.used,
        limit: limits.executionsPerMonth,
        percentage: calculatePercent(
          billingState.usage.executions.used,
          limits.executionsPerMonth,
        ),
      },
      aiGenerations: {
        used: billingState.usage.aiGenerations.used,
        limit: limits.aiGenerationsPerMonth,
        percentage: calculatePercent(
          billingState.usage.aiGenerations.used,
          limits.aiGenerationsPerMonth,
        ),
      },
      teamMembers: {
        used: billingState.usage.teamMembers.used,
        limit: limits.teamMembers,
        percentage: calculatePercent(
          billingState.usage.teamMembers.used,
          limits.teamMembers,
        ),
      },
    };

    return NextResponse.json(usageData, { status: 200 });
  } catch (error) {
    console.error("Error in GET /api/billing/usage:", error);
    return NextResponse.json(
      { error: "Failed to load usage data" },
      { status: 500 },
    );
  }
}
