"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowUpRight, Sparkles, X, AlertCircle } from "lucide-react";

import type { PlanLimitDetail } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

export type UpgradeTrigger =
  | "workflows"
  | "executions"
  | "ai"
  | "seats"
  | "retention"
  | "quota_warning";

export interface UpgradePromptProps {
  /** The specific interaction trigger point that produced the upgrade recommendation. */
  trigger: UpgradeTrigger;
  /** Machine-readable details from PLAN_LIMIT_EXCEEDED error (limitKey, current, max, planId, suggestedPlan). */
  structuredError?: PlanLimitDetail | null;
  /** Current plan ID to dynamically determine suggested upgrade if structuredError is not available. */
  currentPlan?: string;
  /** Explicit suggested plan override (e.g., "PRO", "BUSINESS"). */
  suggestedPlan?: string;
  /** Callback invoked when the user dismisses the prompt. */
  onDismiss?: () => void;
  /** Visual presentation variant. Defaults to "banner". */
  variant?: "banner" | "inline" | "dialog";
  /** Controlled open state for dialog variant. */
  open?: boolean;
  /** Controlled open state change handler for dialog variant. */
  onOpenChange?: (open: boolean) => void;
  className?: string;
}

const STORAGE_KEY_PREFIX = "vantis_upgrade_prompt_dismissed_";

export function isUpgradePromptDismissed(trigger: UpgradeTrigger): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    return window.sessionStorage?.getItem(`${STORAGE_KEY_PREFIX}${trigger}`) === "true";
  } catch {
    return false;
  }
}

export function dismissUpgradePrompt(trigger: UpgradeTrigger): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.sessionStorage?.setItem(`${STORAGE_KEY_PREFIX}${trigger}`, "true");
  } catch {
    // Ignore storage quota or security errors in strict browsing modes
  }
}

export function resetUpgradePromptDismissal(trigger: UpgradeTrigger): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.sessionStorage?.removeItem(`${STORAGE_KEY_PREFIX}${trigger}`);
  } catch {
    // Ignore
  }
}

export function getSuggestedPlanForCurrent(currentPlan?: string): "PRO" | "BUSINESS" {
  if (currentPlan && currentPlan.toUpperCase() === "PRO") {
    return "BUSINESS";
  }
  return "PRO";
}

const PLAN_UPGRADE_INFO: Record<
  "PRO" | "BUSINESS",
  Record<UpgradeTrigger, { title: string; description: string }>
> = {
  PRO: {
    workflows: {
      title: "Workflow Limit Reached",
      description: "You have reached your active workflows limit. Upgrade to Pro for up to 100 active workflows.",
    },
    executions: {
      title: "Monthly Execution Quota Reached",
      description: "You have reached your monthly execution allowance. Upgrade to Pro for 50,000 monthly executions.",
    },
    ai: {
      title: "AI Generation Allowance Reached",
      description: "You have reached your monthly AI prompt quota. Upgrade to Pro for 500 monthly AI generations.",
    },
    seats: {
      title: "Team Seat Limit Reached",
      description: "You have reached the maximum team members for your plan. Upgrade to Pro for up to 10 team seats.",
    },
    retention: {
      title: "Advanced Investigation & Retention",
      description: "Detailed failure investigations and extended audit retention require a Pro tier or higher.",
    },
    quota_warning: {
      title: "Approaching Resource Limits",
      description: "Your workspace has used 80% or more of a plan quota. Consider upgrading before reaching capacity.",
    },
  },
  BUSINESS: {
    workflows: {
      title: "Workflow Limit Reached",
      description: "You have reached your active workflows limit. Upgrade to Business for up to 1,000 active workflows.",
    },
    executions: {
      title: "Monthly Execution Quota Reached",
      description: "You have reached your monthly execution allowance. Upgrade to Business for up to 1,000,000 monthly executions.",
    },
    ai: {
      title: "AI Generation Allowance Reached",
      description: "You have reached your monthly AI prompt quota. Upgrade to Business for up to 10,000 monthly AI generations.",
    },
    seats: {
      title: "Team Seat Limit Reached",
      description: "You have reached the maximum team members for your plan. Upgrade to Business for up to 100 team seats.",
    },
    retention: {
      title: "Custom Volume & Retention",
      description: "Extended 90-day retention and tailored governance require the Business tier.",
    },
    quota_warning: {
      title: "Approaching Resource Limits",
      description: "Your workspace has used 80% or more of a plan quota. Consider upgrading to Business before reaching capacity.",
    },
  },
};

/**
 * Reusable UpgradePrompt component.
 * 
 * Invariants:
 * 1. Dismissible and shown at most once per browser session per trigger (backed by sessionStorage).
 * 2. Consumes machine-readable PlanLimitDetail fields without parsing error messages.
 * 3. Never suppresses or blocks failure alerts / toast notifications.
 */
export function UpgradePrompt({
  trigger,
  structuredError,
  currentPlan,
  suggestedPlan: explicitSuggestedPlan,
  onDismiss,
  variant = "banner",
  open,
  onOpenChange,
  className,
}: UpgradePromptProps) {
  const router = useRouter();
  const [isDismissed, setIsDismissed] = React.useState<boolean>(false);

  // Sync initial dismissed status from sessionStorage
  React.useEffect(() => {
    setIsDismissed(isUpgradePromptDismissed(trigger));
  }, [trigger]);

  const handleDismiss = React.useCallback(() => {
    try {
      dismissUpgradePrompt(trigger);
    } catch {
      // Safe fallback if sessionStorage throws
    }
    setIsDismissed(true);
    onDismiss?.();
    if (onOpenChange) {
      onOpenChange(false);
    }
  }, [trigger, onDismiss, onOpenChange]);

  if (isDismissed) {
    return null;
  }

  const resolvedSuggestedPlan =
    explicitSuggestedPlan ||
    structuredError?.suggestedPlan ||
    (currentPlan ? getSuggestedPlanForCurrent(currentPlan) : "PRO");

  const tierKey: "PRO" | "BUSINESS" =
    resolvedSuggestedPlan.toUpperCase() === "BUSINESS" ? "BUSINESS" : "PRO";

  const tierInfo = PLAN_UPGRADE_INFO[tierKey][trigger];
  const planId = structuredError?.planId || currentPlan || "Current";

  const title = structuredError
    ? `${resolvedSuggestedPlan} Upgrade Recommended`
    : tierInfo.title;

  const description = structuredError
    ? `You have reached the ${structuredError.limitKey || "quota"} limit (${structuredError.current} / ${structuredError.max}) on the ${planId} plan. Upgrade to ${resolvedSuggestedPlan} for higher capacity.`
    : tierInfo.description;

  if (variant === "dialog") {
    const isDialogOpen = open !== undefined ? open : !isDismissed;
    return (
      <Dialog
        open={isDialogOpen}
        onOpenChange={(val) => {
          if (!val) {
            handleDismiss();
          } else {
            onOpenChange?.(val);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <div className="flex items-center gap-2 mb-1">
              <Badge variant="outline" className="border-brand-500/30 text-brand-400 text-[10px] px-1.5 py-0">
                {resolvedSuggestedPlan} PLAN
              </Badge>
            </div>
            <DialogTitle className="text-base font-semibold">{title}</DialogTitle>
            <DialogDescription className="text-xs text-muted-foreground pt-1">
              {description}
            </DialogDescription>
          </DialogHeader>

          <DialogFooter className="gap-2 sm:gap-0 pt-3">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={handleDismiss}
              className="text-xs"
            >
              Dismiss for session
            </Button>
            <Button
              type="button"
              size="sm"
              className="bg-brand-600 hover:bg-brand-500 text-white text-xs"
              onClick={() => {
                handleDismiss();
                router.push("/billing");
              }}
            >
              <span>View Plans & Upgrade</span>
              <ArrowUpRight className="ml-1 size-3.5" />
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    );
  }

  // Inline or Banner variant
  return (
    <div
      role="region"
      aria-label="Plan upgrade recommendation"
      className={cn(
        "relative rounded-xl border border-brand-500/30 bg-brand-500/[0.05] p-3.5 sm:p-4 text-xs transition-all",
        variant === "inline" ? "my-3" : "my-4",
        className,
      )}
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-2.5">
          <div className="flex size-7 shrink-0 items-center justify-center rounded-md bg-brand-500/10 text-brand-400 mt-0.5">
            {trigger === "quota_warning" ? (
              <AlertCircle className="size-4" />
            ) : (
              <Sparkles className="size-4" />
            )}
          </div>
          <div className="space-y-0.5">
            <div className="flex items-center gap-2">
              <span className="font-semibold text-foreground dark:text-white">
                {title}
              </span>
              <Badge
                variant="outline"
                className="border-brand-500/30 text-brand-400 text-[10px] px-1.5 py-0"
              >
                {resolvedSuggestedPlan}
              </Badge>
            </div>
            <p className="text-[11px] text-muted-foreground dark:text-white/60 leading-relaxed">
              {description}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0 self-end sm:self-auto">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={handleDismiss}
            className="h-7 px-2 text-[11px] text-muted-foreground hover:text-foreground dark:text-white/50 dark:hover:text-white"
          >
            Dismiss
          </Button>

          <Button
            type="button"
            size="sm"
            asChild
            className="h-7 px-2.5 bg-brand-600 hover:bg-brand-500 text-white text-[11px] shadow-sm"
          >
            <Link href="/billing">
              <span>Upgrade</span>
              <ArrowUpRight className="ml-1 size-3" />
            </Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
