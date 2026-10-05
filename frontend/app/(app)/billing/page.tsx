"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Check,
  CreditCard,
  Globe,
  Info,
  Layers,
  Sparkles,
  Zap,
  Users,
  ShieldAlert,
  ArrowRight,
  AlertTriangle,
  RefreshCw,
} from "lucide-react";
import { toast } from "sonner";

import { useAuthStore } from "@/lib/auth-store";
import { queryKeys } from "@/lib/queryKeys";
import { fetchBillingPlans, fetchBillingState, fetchBillingUsage } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";
import {
  SUPPORTED_CURRENCIES,
  type BillingInterval,
  type SupportedCurrency,
} from "@/lib/billing/pricing.config";
import { formatIntervalSuffix } from "@/lib/billing/format";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  PageContainer,
  PageHeader,
  StatusBadge,
} from "@/components/app/page-primitives";

export default function BillingPage() {
  const currentOrg = useAuthStore((s) => s.currentOrganization);
  const workspaceId = currentOrg?.id;

  const [interval, setInterval] = useState<BillingInterval>("MONTHLY");
  const [selectedDisplayCurrency, setSelectedDisplayCurrency] =
    useState<SupportedCurrency | "AUTO">("AUTO");

  // Query 1: Billing state (authority for subscription, country, currency)
  const { data: billingState, isLoading: isStateLoading } = useQuery({
    queryKey: queryKeys.billing.state(
      workspaceId,
      selectedDisplayCurrency === "AUTO" ? undefined : selectedDisplayCurrency,
    ),
    queryFn: () =>
      fetchBillingState({
        workspaceId,
        currency:
          selectedDisplayCurrency === "AUTO" ? undefined : selectedDisplayCurrency,
      }),
  });

  const activeDisplayCurrency: SupportedCurrency =
    selectedDisplayCurrency === "AUTO"
      ? (billingState?.displayCurrency ?? "USD")
      : selectedDisplayCurrency;

  // Query 2: Localized plan offerings
  const { data: plansData, isLoading: isPlansLoading } = useQuery({
    queryKey: queryKeys.billing.plans(activeDisplayCurrency, interval),
    queryFn: () =>
      fetchBillingPlans({
        workspaceId,
        currency: activeDisplayCurrency,
        interval,
      }),
    enabled: !!billingState,
  });

  // Query 3: Real workspace usage metrics
  const {
    data: usageData,
    isLoading: isUsageLoading,
    isError: isUsageError,
    refetch: refetchUsage,
  } = useQuery({
    queryKey: queryKeys.billing.usage(workspaceId),
    queryFn: () => fetchBillingUsage(workspaceId),
  });

  const isAnyLimitReached = usageData
    ? (usageData.executions.used >= usageData.executions.limit && usageData.executions.limit > 0) ||
      (usageData.aiGenerations.used >= usageData.aiGenerations.limit && usageData.aiGenerations.limit > 0) ||
      (usageData.workflows.used >= usageData.workflows.limit && usageData.workflows.limit > 0) ||
      (usageData.teamMembers.used >= usageData.teamMembers.limit && usageData.teamMembers.limit > 0)
    : false;

  const isBeta = billingState?.isPrivateBeta ?? true;
  const isBillingEnabled = billingState?.isBillingEnabled ?? false;
  const billingCurrency = billingState?.billingCurrency ?? "USD";
  const billingCountry = billingState?.billingCountry ?? "US";

  const isCurrencyMismatched =
    activeDisplayCurrency !== billingCurrency;

  const handleCheckoutClick = (planId: string) => {
    if (!isBillingEnabled) {
      toast.info("Billing will be available after the private beta.", {
        description: "All Pro features are currently unlocked for your team at no charge.",
      });
      return;
    }

    toast.info(`Preparing upgrade to ${planId}...`);
  };

  return (
    <PageContainer variant="wide" className="space-y-10">
      <PageHeader
        eyebrow="Organization"
        title="Billing & Usage"
        description="Private Beta — Pro features are currently free."
      />

      {/* Private Beta Announcement Banner */}
      {isBeta && (
        <div className="relative overflow-hidden rounded-xl border border-brand-500/20 bg-brand-500/[0.04] p-4 sm:p-5 backdrop-blur-sm">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-start gap-3">
              <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-brand-500/10 text-brand-400">
                <Sparkles className="size-4" />
              </div>
              <div className="space-y-0.5">
                <div className="flex items-center gap-2">
                  <h3 className="text-sm font-medium text-white">
                    Private Beta Access Active
                  </h3>
                  <Badge variant="outline" className="border-brand-500/30 text-brand-400 text-[10px] px-1.5 py-0">
                    Pro Unlocked
                  </Badge>
                </div>
                <p className="text-xs text-white/60">
                  During the Private Beta, your workspace has complimentary access to all Pro features,
                  including AI generation, reliability monitoring, and priority support. No payment method required.
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <Badge variant="secondary" className="font-mono text-[11px] bg-white/[0.06] text-white/80">
                Region: {billingCountry} · {billingCurrency}
              </Badge>
            </div>
          </div>
        </div>
      )}

      {/* Current Plan & Usage Strip */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Current Plan Overview Card */}
        <Card className="flex flex-col justify-between border-white/[0.08] bg-[#0a0a0c]">
          <CardHeader className="pb-4">
            <div className="flex items-center justify-between">
              <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-white/40">
                Current Subscription
              </span>
              <StatusBadge status="ACTIVE" label={isBeta ? "PRIVATE BETA" : "ACTIVE"} />
            </div>
            <CardTitle className="pt-2 text-2xl font-bold tracking-tight text-white">
              {isBeta ? "Private Beta" : (billingState?.activePlan ?? "Free")}
            </CardTitle>
            <CardDescription className="text-xs text-white/50">
              {isBeta
                ? "Full access to Pro features without charge during preview."
                : "Standard tier automation limits for your workspace."}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4 pb-4">
            <div className="flex items-baseline gap-2">
              <span className="text-3xl font-semibold tracking-tight text-white">
                {billingState?.currencySymbol ?? "$"}0
              </span>
              <span className="text-xs text-white/40">
                {formatIntervalSuffix("MONTHLY")}
              </span>
            </div>

            <div className="rounded-lg border border-white/[0.05] bg-white/[0.02] p-3 text-xs space-y-1.5">
              <div className="flex items-center justify-between text-white/60">
                <span>Billing Currency</span>
                <span className="font-mono font-medium text-white">{billingCurrency}</span>
              </div>
              <div className="flex items-center justify-between text-white/60">
                <span>Billing Country</span>
                <span className="font-mono font-medium text-white">{billingCountry}</span>
              </div>
              <div className="flex items-center justify-between text-white/60">
                <span>Payment Method</span>
                <span className="text-white/40">None (Free Beta)</span>
              </div>
            </div>
          </CardContent>
          <CardFooter className="pt-2 border-t border-white/[0.05] text-[11px] text-white/40">
            {isBeta
              ? "No automated charges will occur during beta."
              : "Manage plan details or payment methods below."}
          </CardFooter>
        </Card>

        {/* Real Usage Progress Bars (Spans 2 cols on lg) */}
        <Card className="border-white/[0.08] bg-[#0a0a0c] lg:col-span-2">
          <CardHeader className="pb-4">
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base font-semibold text-white">
                  Monthly Resource Usage
                </CardTitle>
                <CardDescription className="text-xs text-white/50">
                  Usage resets automatically at the start of each calendar billing cycle.
                </CardDescription>
              </div>
              <Badge variant="outline" className="border-white/[0.08] text-white/60 text-[11px]">
                Cycle: Current Month
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-5">
            {isUsageLoading ? (
              <UsageSkeleton />
            ) : isUsageError ? (
              <UsageErrorState onRetry={() => refetchUsage()} />
            ) : usageData ? (
              <>
                {isAnyLimitReached && (
                  <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 p-3 text-xs flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-rose-300">
                      <AlertTriangle className="size-4 shrink-0 text-rose-400" />
                      <span>
                        You have reached a plan limit for this billing period. Contact our team to increase your beta workspace quota.
                      </span>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      className="border-rose-500/40 text-rose-300 hover:bg-rose-500/20 text-xs shrink-0 self-start sm:self-auto h-7 px-2.5"
                      onClick={() =>
                        window.open(
                          "mailto:support@vantis.ai?subject=Beta%20Quota%20Increase%20Request",
                          "_blank",
                        )
                      }
                    >
                      Contact Team
                    </Button>
                  </div>
                )}

                {/* Executions */}
                <UsageProgressBar
                  icon={<Zap className="size-3.5 text-amber-400" />}
                  label="Executions"
                  used={usageData.executions.used}
                  limit={usageData.executions.limit}
                  unit="executions"
                />

                {/* AI Generations */}
                <UsageProgressBar
                  icon={<Sparkles className="size-3.5 text-brand-400" />}
                  label="AI Generations"
                  used={usageData.aiGenerations.used}
                  limit={usageData.aiGenerations.limit}
                  unit="prompts"
                />

                {/* Workflows */}
                <UsageProgressBar
                  icon={<Layers className="size-3.5 text-emerald-400" />}
                  label="Active Workflows"
                  used={usageData.workflows.used}
                  limit={usageData.workflows.limit}
                  unit="workflows"
                />

                {/* Team Members */}
                <UsageProgressBar
                  icon={<Users className="size-3.5 text-sky-400" />}
                  label="Team Members"
                  used={usageData.teamMembers.used}
                  limit={usageData.teamMembers.limit}
                  unit="seats"
                />
              </>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* Plan Selection Section */}
      <div className="space-y-6 pt-4">
        {/* Interval and Currency Controls Row */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b border-white/[0.06] pb-5">
          <div>
            <h2 className="text-lg font-semibold tracking-tight text-white">
              Available Plans
            </h2>
            <p className="text-xs text-white/50">
              Select an option to review features and limits.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {/* Monthly / Yearly Toggle */}
            <div className="inline-flex items-center rounded-lg border border-white/[0.08] bg-[#111114] p-1">
              <button
                type="button"
                onClick={() => setInterval("MONTHLY")}
                className={cn(
                  "rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                  interval === "MONTHLY"
                    ? "bg-white/[0.08] text-white shadow-sm"
                    : "text-white/50 hover:text-white",
                )}
              >
                Monthly
              </button>
              <button
                type="button"
                onClick={() => setInterval("YEARLY")}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                  interval === "YEARLY"
                    ? "bg-white/[0.08] text-white shadow-sm"
                    : "text-white/50 hover:text-white",
                )}
              >
                <span>Yearly</span>
                <span className="rounded bg-brand-500/20 px-1 py-0.5 text-[9px] font-semibold text-brand-400">
                  Save ~17%
                </span>
              </button>
            </div>

            {/* Display Currency Selector */}
            <div className="flex items-center gap-2">
              <Globe className="size-3.5 text-white/40" />
              <Select
                value={activeDisplayCurrency}
                onValueChange={(val) =>
                  setSelectedDisplayCurrency(val as SupportedCurrency)
                }
              >
                <SelectTrigger className="w-[110px] h-8 text-xs border-white/[0.08] bg-[#111114] text-white">
                  <SelectValue placeholder="Currency" />
                </SelectTrigger>
                <SelectContent className="border-white/[0.08] bg-[#0e1015] text-white">
                  {SUPPORTED_CURRENCIES.map((cur) => (
                    <SelectItem key={cur} value={cur} className="text-xs">
                      {cur}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
        </div>

        {/* Currency Mismatch Clarification Notice */}
        {isCurrencyMismatched && (
          <div className="flex items-center gap-2.5 rounded-lg border border-amber-500/20 bg-amber-500/[0.04] p-3 text-xs text-amber-200/80">
            <Info className="size-4 shrink-0 text-amber-400" />
            <p>
              Display currency is set to <span className="font-semibold text-amber-200">{activeDisplayCurrency}</span> for preview purposes.
              Actual billing occurs in your region's currency: <span className="font-semibold text-amber-200">{billingCurrency} ({billingCountry})</span>.
            </p>
          </div>
        )}

        {/* 4 Plans Grid: Free, Private Beta, Pro, Business */}
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4">
          {plansData?.plans.map((plan) => {
            const isBetaPlan = plan.id === "PRIVATE_BETA";
            const isProPlan = plan.id === "PRO";
            const isFreePlan = plan.id === "FREE";
            const isBusinessPlan = plan.id === "BUSINESS";
            const isCurrent = isBeta ? isBetaPlan : plan.isCurrent;

            return (
              <Card
                key={plan.id}
                className={cn(
                  "relative flex flex-col justify-between border-white/[0.08] bg-[#0a0a0c] transition-all",
                  isCurrent && "border-brand-500/40 shadow-sm shadow-brand-500/5",
                  isProPlan && !isCurrent && "border-white/[0.12]",
                )}
              >
                {/* Header with Badges */}
                <CardHeader className="pb-4">
                  <div className="flex items-center justify-between min-h-[24px]">
                    <span className="font-mono text-xs font-semibold uppercase tracking-wider text-white/50">
                      {plan.name}
                    </span>
                    {isCurrent && (
                      <Badge className="bg-brand-500/20 text-brand-300 border-brand-500/30 text-[10px] px-2">
                        CURRENT
                      </Badge>
                    )}
                    {isBetaPlan && !isCurrent && (
                      <Badge variant="outline" className="border-emerald-500/30 text-emerald-400 text-[10px]">
                        FREE IN BETA
                      </Badge>
                    )}
                  </div>
                  <CardTitle className="pt-1 text-xl font-bold tracking-tight text-white">
                    {plan.name}
                  </CardTitle>
                  <CardDescription className="text-xs text-white/50 line-clamp-2 min-h-[32px]">
                    {plan.tagline}
                  </CardDescription>
                </CardHeader>

                {/* Price Display */}
                <CardContent className="space-y-5 pb-6">
                  <div className="border-y border-white/[0.05] py-4">
                    {isBusinessPlan ? (
                      <div>
                        <div className="text-2xl font-bold tracking-tight text-white">
                          Custom
                        </div>
                        <p className="text-[11px] text-white/40">
                          Contact our team for tailored quotas & SLAs
                        </p>
                      </div>
                    ) : (
                      <div className="space-y-1">
                        {/* If PRO in Private Beta: show planned struck-through price */}
                        {isProPlan && isBeta && plan.pricing?.formattedStrikethrough && (
                          <div className="flex items-center gap-2">
                            <span className="text-sm line-through text-white/40">
                              {plan.pricing.formattedStrikethrough}
                            </span>
                            <Badge variant="outline" className="border-brand-500/30 text-brand-400 text-[10px] px-1.5 py-0">
                              Private Beta
                            </Badge>
                          </div>
                        )}

                        <div className="flex items-baseline gap-1.5">
                          <span className="text-2xl font-bold tracking-tight text-white">
                            {isBeta && (isBetaPlan || isProPlan)
                              ? `${plan.pricing?.currencySymbol ?? "$" }0`
                              : (plan.pricing?.formatted ?? "$0")}
                          </span>
                          <span className="text-xs text-white/40">
                            {formatIntervalSuffix(interval)}
                          </span>
                        </div>

                        {isBeta && (isBetaPlan || isProPlan) && (
                          <p className="text-[11px] text-brand-400 font-medium">
                            Free during Private Beta
                          </p>
                        )}
                      </div>
                    )}
                  </div>

                  {/* Feature Checklist */}
                  <div className="space-y-2.5">
                    <p className="text-[11px] font-medium text-white/40 uppercase tracking-wider">
                      Included Capabilities
                    </p>
                    <ul className="space-y-2 text-xs text-white/70">
                      {plan.features.map((feat, idx) => (
                        <li key={idx} className="flex items-start gap-2">
                          <Check className="size-3.5 shrink-0 text-emerald-400 mt-0.5" />
                          <span>{feat}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </CardContent>

                {/* Card CTA Footer */}
                <CardFooter className="pt-2">
                  {isCurrent ? (
                    <Button
                      variant="outline"
                      className="w-full border-white/[0.08] bg-white/[0.04] text-white/70 cursor-default"
                      disabled
                    >
                      Current Plan
                    </Button>
                  ) : isBusinessPlan ? (
                    <Button
                      variant="outline"
                      className="w-full border-white/[0.12] hover:bg-white/[0.06] text-white"
                      asChild
                    >
                      <a href="mailto:sales@vantis.run?subject=Vantis%20Business%20Inquiry">
                        Contact Sales
                      </a>
                    </Button>
                  ) : isProPlan ? (
                    <Button
                      variant={isBillingEnabled ? "default" : "secondary"}
                      className={cn(
                        "w-full",
                        isBillingEnabled
                          ? "bg-brand-600 hover:bg-brand-500 text-white"
                          : "bg-white/[0.04] text-white/60 border border-white/[0.08] hover:bg-white/[0.06]",
                      )}
                      onClick={() => handleCheckoutClick(plan.id)}
                    >
                      {isBillingEnabled ? (
                        <span className="flex items-center gap-1.5">
                          Upgrade to Pro <ArrowRight className="size-3.5" />
                        </span>
                      ) : (
                        <span>Included in Beta</span>
                      )}
                    </Button>
                  ) : (
                    <Button
                      variant="ghost"
                      className="w-full text-white/40 hover:text-white text-xs"
                      disabled
                    >
                      Downgrade
                    </Button>
                  )}
                </CardFooter>
              </Card>
            );
          })}
        </div>
      </div>
    </PageContainer>
  );
}

/**
 * Loading skeleton for usage counters.
 */
function UsageSkeleton() {
  return (
    <div className="space-y-5">
      {[1, 2, 3, 4].map((i) => (
        <div key={i} className="space-y-2">
          <div className="flex items-center justify-between">
            <Skeleton className="h-4 w-28" />
            <Skeleton className="h-4 w-24" />
          </div>
          <Skeleton className="h-2 w-full rounded-full" />
        </div>
      ))}
    </div>
  );
}

/**
 * Error state with retry when telemetry fails.
 */
function UsageErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-6 text-center space-y-3">
      <div className="flex size-9 items-center justify-center rounded-lg bg-rose-500/10 text-rose-400">
        <AlertTriangle className="size-4" />
      </div>
      <div className="space-y-1">
        <p className="text-xs font-medium text-white">Failed to load real usage metrics</p>
        <p className="text-[11px] text-white/50">Unable to reach the workspace resource telemetry service.</p>
      </div>
      <Button
        variant="outline"
        size="sm"
        onClick={onRetry}
        className="h-7 text-xs border-white/[0.1] text-white/80 hover:bg-white/[0.05]"
      >
        <RefreshCw className="mr-1.5 size-3" />
        Retry
      </Button>
    </div>
  );
}

/**
 * Reusable resource usage progress bar component with strict threshold indicators:
 * <70% normal, 70-89% approaching limit, 90-99% near limit, >=100% limit reached.
 */
function UsageProgressBar({
  icon,
  label,
  used,
  limit,
  unit,
}: {
  icon: React.ReactNode;
  label: string;
  used: number;
  limit: number;
  unit: string;
}) {
  const percentage = Number.isFinite(limit) && limit > 0
    ? Math.min(100, Math.round((used / limit) * 100))
    : 0;

  let statusBadge: React.ReactNode = null;
  let barColor = "bg-brand-500";

  if (percentage >= 100) {
    barColor = "bg-rose-500";
    statusBadge = (
      <Badge variant="outline" className="border-rose-500/30 bg-rose-500/10 text-rose-400 text-[10px] px-1.5 py-0 font-normal">
        Limit reached
      </Badge>
    );
  } else if (percentage >= 90) {
    barColor = "bg-orange-500";
    statusBadge = (
      <Badge variant="outline" className="border-orange-500/30 bg-orange-500/10 text-orange-400 text-[10px] px-1.5 py-0 font-normal">
        Near limit
      </Badge>
    );
  } else if (percentage >= 70) {
    barColor = "bg-amber-400";
    statusBadge = (
      <Badge variant="outline" className="border-amber-400/30 bg-amber-400/10 text-amber-400 text-[10px] px-1.5 py-0 font-normal">
        Approaching limit
      </Badge>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between text-xs">
        <div className="flex items-center gap-2">
          {icon}
          <span className="font-medium text-white/80">{label}</span>
          {statusBadge}
        </div>
        <div className="font-mono text-white/60">
          <span className="text-white font-medium">{used.toLocaleString()}</span>
          {" / "}
          <span>{Number.isFinite(limit) ? limit.toLocaleString() : "Unlimited"}</span>{" "}
          <span className="text-white/40 text-[10px]">{unit}</span>
        </div>
      </div>

      <div className="h-2 w-full overflow-hidden rounded-full bg-white/[0.05]">
        <div
          className={cn(
            "h-full rounded-full transition-all duration-500",
            barColor,
          )}
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
}
