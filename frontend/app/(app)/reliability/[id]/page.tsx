"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Link from "next/link";
import {
  ArrowLeft,
  Brain,
  CheckCircle2,
  Copy,
  Loader2,
  MoreHorizontal,
  RefreshCw,
} from "lucide-react";
import {
  format,
  formatDistanceToNowStrict,
  isValid,
} from "date-fns";
import { toast } from "sonner";

import {
  fetchAnomaly,
  acknowledgeAnomaly,
  resolveAnomaly,
  markAnomalyFalsePositive,
  investigateAnomaly,
  verifyRecovery,
  fetchRecoveryStatus,
} from "@/lib/api";

import type {
  AnomalyDetail,
  AnomalySeverity,
  AnomalyStatus,
  AIInvestigationResult,
  RecoveryStatus,
} from "@/types";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  ErrorState,
  PageContainer,
  PageHeader,
  TableSkeleton,
} from "@/components/app/page-primitives";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/* -------------------------------------------------------------------------- */
/* Display config                                                             */
/* -------------------------------------------------------------------------- */

const RECOVERY_REQUIRED_COUNT = 5;
const RECOVERY_POLL_MS = 3000;
const REASON_MAX_LENGTH = 500;

const SEVERITY: Record<
  AnomalySeverity,
  {
    label: string;
    dot: string;
    text: string;
  }
> = {
  LOW: {
    label: "Low",
    dot: "bg-green-400",
    text: "text-white/60",
  },
  MEDIUM: {
    label: "Medium",
    dot: "bg-yellow-400",
    text: "text-white/70",
  },
  HIGH: {
    label: "High",
    dot: "bg-orange-400",
    text: "text-white/80",
  },
  CRITICAL: {
    label: "Critical",
    dot: "bg-red-400",
    text: "text-red-300",
  },
};

const STATUS: Record<
  AnomalyStatus,
  {
    label: string;
    className: string;
  }
> = {
  OPEN: {
    label: "Open",
    className:
      "bg-blue-500/10 text-blue-400 border-blue-500/20",
  },

  ACKNOWLEDGED: {
    label: "Acknowledged",
    className:
      "bg-purple-500/10 text-purple-400 border-purple-500/20",
  },

  VERIFYING_RECOVERY: {
    label: "Verifying recovery",
    className:
      "bg-amber-500/10 text-amber-400 border-amber-500/20",
  },

  RESOLVED: {
    label: "Resolved",
    className:
      "bg-green-500/10 text-green-400 border-green-500/20",
  },

  FALSE_POSITIVE: {
    label: "False positive",
    className:
      "bg-gray-500/10 text-gray-400 border-gray-500/20",
  },
};

const TYPE_LABEL: Record<string, string> = {
  VOLUME: "Volume",
  LATENCY: "Latency",
  OUTPUT: "Output",
  BEHAVIORAL: "Behavioral",
};

const TAB_TRIGGER =
  "data-[state=active]:bg-white/[0.08] data-[state=active]:text-white";

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

const isFiniteNumber = (
  v: unknown
): v is number =>
  typeof v === "number" && Number.isFinite(v);

const formatPercent = (v: unknown) =>
  isFiniteNumber(v)
    ? `${Math.round(v * 100)}%`
    : "—";

const formatSigma = (v: unknown) =>
  isFiniteNumber(v)
    ? `${v > 0 ? "+" : ""}${v.toFixed(2)}σ`
    : "—";

function FormattedDate({
  value,
  pattern = "MMM d, yyyy HH:mm:ss",
  relative = false,
}: {
  value?: string | null;
  pattern?: string;
  relative?: boolean;
}) {
  const date = value ? new Date(value) : null;

  if (!date || !isValid(date)) {
    return <span>—</span>;
  }

  return (
    <time
      dateTime={date.toISOString()}
      title={
        relative
          ? format(
              date,
              "MMM d, yyyy 'at' HH:mm:ss"
            )
          : undefined
      }
    >
      {relative
        ? formatDistanceToNowStrict(date, {
            addSuffix: true,
          })
        : format(date, pattern)}
    </time>
  );
}

/* -------------------------------------------------------------------------- */
/* Small presentational pieces                                                */
/* -------------------------------------------------------------------------- */

function SeverityLabel({
  severity,
}: {
  severity: AnomalySeverity;
}) {
  const config = SEVERITY[severity];

  if (!config) {
    return (
      <span className="text-sm text-white/44">
        {severity}
      </span>
    );
  }

  return (
    <span
      className={`inline-flex items-center gap-2 text-sm ${config.text}`}
    >
      <span
        className={`size-1.5 rounded-full ${config.dot}`}
        aria-hidden
      />

      {config.label} severity
    </span>
  );
}

function StatusBadge({
  status,
}: {
  status: AnomalyStatus;
}) {
  const config = STATUS[status];

  return (
    <Badge
      variant="secondary"
      className={
        config?.className ?? "text-white/60"
      }
    >
      {config?.label ?? status}
    </Badge>
  );
}

function Panel({
  title,
  description,
  action,
  className = "",
  children,
}: {
  title: string;
  description?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section
      className={`rounded-xl border border-white/[0.08] bg-[#0a0a0a] ${className}`}
    >
      <header className="flex items-start justify-between gap-4 px-5 pt-5">
        <div className="min-w-0">
          <h2 className="text-sm font-medium text-white/90">
            {title}
          </h2>

          {description && (
            <p className="mt-1 text-sm text-white/44">
              {description}
            </p>
          )}
        </div>

        {action}
      </header>

      <div className="px-5 pb-5 pt-5">
        {children}
      </div>
    </section>
  );
}

function Field({
  label,
  children,
  mono = false,
}: {
  label: string;
  children: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-white/44">
        {label}
      </dt>

      <dd
        className={`mt-1 break-words text-sm text-white/90 ${
          mono ? "font-mono tabular-nums" : ""
        }`}
      >
        {children}
      </dd>
    </div>
  );
}

function InvestigationSection({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium text-white/90">
        {title}
      </h3>

      {children}
    </div>
  );
}

function DetailSkeleton() {
  return (
    <PageContainer
      variant="narrow"
      aria-hidden
    >
      <div className="h-4 w-32 animate-pulse rounded bg-white/[0.06]" />

      <div className="space-y-3">
        <div className="h-6 w-80 animate-pulse rounded bg-white/[0.06]" />

        <div className="h-4 w-56 animate-pulse rounded bg-white/[0.06]" />
      </div>

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div className="h-72 animate-pulse rounded-xl bg-white/[0.04]" />

        <div className="h-72 animate-pulse rounded-xl bg-white/[0.04]" />
      </div>
    </PageContainer>
  );
}

/* -------------------------------------------------------------------------- */
/* Page                                                                       */
/* -------------------------------------------------------------------------- */

type PendingAction =
  | "acknowledge"
  | "resolve"
  | "verify"
  | "investigate";

export default function AnomalyDetailPage({
  params,
}: {
  params: { id: string };
}) {
  const { id } = params;

  const [anomaly, setAnomaly] =
    useState<AnomalyDetail | null>(null);

  const [investigation, setInvestigation] =
    useState<AIInvestigationResult | null>(null);

  const [recoveryStatus, setRecoveryStatus] =
    useState<RecoveryStatus | null>(null);

  const [loading, setLoading] =
    useState(true);

  const [error, setError] =
    useState<string | null>(null);

  const [pending, setPending] =
    useState<PendingAction | null>(null);

  const [activeTab, setActiveTab] =
    useState("overview");

  const [confirmResolveOpen, setConfirmResolveOpen] =
    useState(false);

  const [fpOpen, setFpOpen] =
    useState(false);

  const [fpReason, setFpReason] =
    useState("");

  const [fpSubmitting, setFpSubmitting] =
    useState(false);

  // Guards against out-of-order responses
  // and updates after unmount.
  const requestId = useRef(0);

  const refresh = useCallback(
    async ({
      silent = false,
    }: {
      silent?: boolean;
    } = {}) => {
      const req = ++requestId.current;

      if (!silent) {
        setLoading(true);
        setError(null);
      }

      try {
        const data = await fetchAnomaly(id);

        if (req !== requestId.current) {
          return;
        }

        setAnomaly(data);

        const saved =
          data.evidence?.investigation as
            | AIInvestigationResult
            | undefined;

        if (saved) {
          setInvestigation(saved);
        }

        if (
          data.status ===
            "VERIFYING_RECOVERY" ||
          data.status === "RESOLVED"
        ) {
          try {
            const snapshot =
              await fetchRecoveryStatus(id);

            if (
              req === requestId.current
            ) {
              setRecoveryStatus(snapshot);
            }
          } catch (err) {
            // Expected for anomalies resolved manually,
            // which never had a recovery run.
            console.error(
              "Failed to load recovery status",
              err
            );
          }
        }
      } catch (err) {
        if (req !== requestId.current) {
          return;
        }

        console.error(
          "Failed to load anomaly",
          err
        );

        if (!silent) {
          setError(
            "We couldn't load this anomaly. Check your connection and try again."
          );
        }
      } finally {
        if (req === requestId.current) {
          setLoading(false);
        }
      }
    },
    [id]
  );

  useEffect(() => {
    setInvestigation(null);
    setRecoveryStatus(null);
    setActiveTab("overview");

    void refresh();
    const reqRef = requestId;

    return () => {
      reqRef.current++;
    };
  }, [refresh]);

  /* ------------------------------------------------------------------------ */
  /* Recovery polling                                                         */
  /* ------------------------------------------------------------------------ */

  const status = anomaly?.status;

  useEffect(() => {
    if (status !== "VERIFYING_RECOVERY") {
      return;
    }

    let stopped = false;
    let inFlight = false;

    const tick = async () => {
      if (inFlight || document.hidden) {
        return;
      }

      inFlight = true;

      try {
        const snapshot =
          await fetchRecoveryStatus(id);

        if (stopped) {
          return;
        }

        setRecoveryStatus(snapshot);

        // The backend can resolve the anomaly on
        // its own once enough healthy executions
        // are observed.
        if (
          snapshot.anomalyStatus !==
          "VERIFYING_RECOVERY"
        ) {
          const updated =
            await fetchAnomaly(id);

          if (stopped) {
            return;
          }

          setAnomaly(updated);

          if (
            updated.status === "RESOLVED"
          ) {
            toast.success(
              "Recovery verified. Anomaly resolved."
            );
          }
        }
      } catch (err) {
        console.error(
          "Failed to poll recovery status",
          err
        );
      } finally {
        inFlight = false;
      }
    };

    void tick();

    const interval = setInterval(
      tick,
      RECOVERY_POLL_MS
    );

    return () => {
      stopped = true;
      clearInterval(interval);
    };
  }, [id, status]);

  /* ------------------------------------------------------------------------ */
  /* Actions                                                                  */
  /* ------------------------------------------------------------------------ */

  async function run(
    kind: PendingAction,
    task: () => Promise<unknown>,
    success: string,
    failure: string
  ): Promise<boolean> {
    setPending(kind);

    try {
      await task();

      await refresh({
        silent: true,
      });

      toast.success(success);

      return true;
    } catch (err) {
      console.error(err);

      toast.error(failure);

      return false;
    } finally {
      setPending(null);
    }
  }

  const handleAcknowledge = () =>
    run(
      "acknowledge",
      () => acknowledgeAnomaly(id),
      "Anomaly acknowledged",
      "Couldn't acknowledge the anomaly. Try again."
    );

  const handleVerify = () =>
    run(
      "verify",
      async () => {
        const updated =
          await verifyRecovery(
            id,
            RECOVERY_REQUIRED_COUNT
          );

        setAnomaly(updated);
      },
      "Recovery verification started",
      "Couldn't start recovery verification. Try again."
    );

  async function handleResolve() {
    const ok = await run(
      "resolve",
      () => resolveAnomaly(id),
      "Anomaly resolved",
      "Couldn't resolve the anomaly. Try again."
    );

    if (ok) {
      setConfirmResolveOpen(false);
    }
  }

  async function handleInvestigate() {
    const ok = await run(
      "investigate",
      async () => {
        const result =
          await investigateAnomaly(id);

        setInvestigation(result.result);
      },
      "Investigation complete",
      "Couldn't run the investigation. Try again."
    );

    if (ok) {
      setActiveTab("investigation");
    }
  }

  function closeFalsePositive() {
    setFpOpen(false);
    setFpReason("");
  }

  async function submitFalsePositive(
    e: React.FormEvent
  ) {
    e.preventDefault();

    const reason = fpReason.trim();

    if (!reason || fpSubmitting) {
      return;
    }

    setFpSubmitting(true);

    try {
      await markAnomalyFalsePositive(
        id,
        reason
      );

      await refresh({
        silent: true,
      });

      toast.success(
        "Marked as false positive"
      );

      closeFalsePositive();
    } catch (err) {
      console.error(err);

      toast.error(
        "Couldn't mark the anomaly as a false positive. Try again."
      );
    } finally {
      setFpSubmitting(false);
    }
  }

  async function copyEvidence(
    value: unknown
  ) {
    try {
      await navigator.clipboard.writeText(
        JSON.stringify(value, null, 2)
      );

      toast.success("Copied to clipboard");
    } catch {
      toast.error(
        "Couldn't copy to clipboard"
      );
    }
  }

  /* ------------------------------------------------------------------------ */
  /* Derived                                                                  */
  /* ------------------------------------------------------------------------ */

  // The investigation is rendered in its own tab,
  // so drop it from the raw dump.
  const detectorEvidence = useMemo(() => {
    const evidence =
      anomaly?.evidence as
        | Record<string, unknown>
        | null
        | undefined;

    if (!evidence) {
      return null;
    }

    const rest = {
      ...evidence,
    };

    delete rest.investigation;

    return Object.keys(rest).length > 0
      ? rest
      : null;
  }, [anomaly?.evidence]);

  const timeline = useMemo(() => {
    if (!anomaly) {
      return [];
    }

    const events: {
      key: string;
      label: string;
      at: string;
      dot: string;
    }[] = [
      {
        key: "created",
        label: "Created",
        at: anomaly.createdAt,
        dot: "bg-white/30",
      },
      {
        key: "detected",
        label: "Detected",
        at: anomaly.detectedAt,
        dot: "bg-blue-400",
      },
    ];

    if (recoveryStatus?.startedAt) {
      events.push({
        key: "recovery",
        label: "Recovery verification started",
        at: recoveryStatus.startedAt,
        dot: "bg-amber-400",
      });
    }

    if (
      anomaly.updatedAt !==
      anomaly.createdAt
    ) {
      events.push({
        key: "updated",
        label: "Last updated",
        at: anomaly.updatedAt,
        dot: "bg-white/30",
      });
    }

    return events
      .filter((e) =>
        isValid(new Date(e.at))
      )
      .sort(
        (a, b) =>
          new Date(a.at).getTime() -
          new Date(b.at).getTime()
      );
  }, [
    anomaly,
    recoveryStatus?.startedAt,
  ]);

  /* ------------------------------------------------------------------------ */
  /* Loading / Error                                                          */
  /* ------------------------------------------------------------------------ */

  if (loading && !anomaly) {
    return <DetailSkeleton />;
  }

  if (error || !anomaly) {
    return (
      <PageContainer variant="narrow">
        <PageHeader
          backLink={{ href: "/reliability", label: "Anomalies" }}
          eyebrow="ANOMALY DETAIL"
          title="Anomaly not found"
        />
        <ErrorState
          title="Failed to load anomaly"
          message={error ?? "This anomaly doesn't exist."}
          onRetry={error ? () => void refresh() : undefined}
        />
      </PageContainer>
    );
  }

  /* ------------------------------------------------------------------------ */
  /* Page state                                                               */
  /* ------------------------------------------------------------------------ */

  const isOpen =
    anomaly.status === "OPEN";

  const isAcknowledged =
    anomaly.status === "ACKNOWLEDGED";

  const isVerifying =
    anomaly.status ===
    "VERIFYING_RECOVERY";

  const isActive =
    isOpen ||
    isAcknowledged ||
    isVerifying;

  const typeLabel =
    TYPE_LABEL[anomaly.type] ??
    anomaly.type;

  /* ------------------------------------------------------------------------ */
  /* Recovery panel state                                                     */
  /* ------------------------------------------------------------------------ */

  const requiredCount =
    recoveryStatus?.requiredCount ??
    anomaly.recoveryRequiredCount ??
    RECOVERY_REQUIRED_COUNT;

  const healthyCount =
    recoveryStatus?.healthyCount ??
    anomaly.recoveryHealthyCount ??
    0;

  const observedCount =
    recoveryStatus?.observedCount ?? 0;

  const recoveryVerified =
    anomaly.status === "RESOLVED" &&
    !!recoveryStatus &&
    healthyCount >= requiredCount;

  const showRecoveryPanel =
    isVerifying || recoveryVerified;

  const recoveryFailing =
    !recoveryVerified &&
    observedCount > 0 &&
    healthyCount === 0;

  const recoveryBadge = recoveryVerified
    ? {
        text: "Verified",
        className:
          "bg-green-500/10 text-green-400 border-green-500/20",
      }
    : recoveryFailing
    ? {
        text: "Not recovering",
        className:
          "bg-red-500/10 text-red-400 border-red-500/20",
      }
    : observedCount > 0
    ? {
        text: "Verifying",
        className:
          "bg-amber-500/10 text-amber-400 border-amber-500/20",
      }
    : {
        text: "Waiting for data",
        className:
          "bg-amber-500/10 text-amber-400 border-amber-500/20",
      };

  const recoveryDescription =
    recoveryVerified
      ? `The workflow is back within its baseline. ${healthyCount} healthy executions observed.`
      : recoveryFailing
      ? "The metric is still outside its baseline range. You can resolve manually, but recovery hasn't been verified."
      : "Observing new executions to confirm the metric returns to its baseline.";

  /* ------------------------------------------------------------------------ */
  /* Render                                                                   */
  /* ------------------------------------------------------------------------ */

  return (
    <PageContainer variant="narrow" className="space-y-6">
      <PageHeader
        backLink={{ href: "/reliability", label: "Anomalies" }}
        eyebrow="ANOMALY DETAIL"
        title={
          <span>
            {typeLabel} anomaly in{" "}
            <Link
              href={`/workflows/${anomaly.workflowId}`}
              className="underline decoration-white/20 underline-offset-4 transition-colors hover:decoration-white/60"
            >
              {anomaly.workflowName}
            </Link>
          </span>
        }
        description={
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 pt-1">
            <StatusBadge status={anomaly.status} />
            <SeverityLabel severity={anomaly.severity} />
            <span className="text-sm text-white/44">
              Detected{" "}
              <FormattedDate
                value={anomaly.detectedAt}
                relative
              />
            </span>
          </div>
        }
        actions={
          <div className="flex shrink-0 items-center gap-2">
            <Button
              variant="outline"
              onClick={() =>
                void handleInvestigate()
              }
              disabled={
                pending === "investigate"
              }
              className="gap-2"
            >
              {pending === "investigate" ? (
                <Loader2
                  className="size-4 animate-spin"
                  aria-hidden
                />
              ) : (
                <Brain
                  className="size-4"
                  aria-hidden
                />
              )}

              {pending === "investigate"
                ? "Investigating…"
                : "Investigate with AI"}
            </Button>

            {(isOpen ||
              isAcknowledged) && (
              <Button
                onClick={() =>
                  void handleVerify()
                }
                disabled={
                  pending === "verify"
                }
                className="gap-2"
              >
                {pending === "verify" && (
                  <Loader2
                    className="size-4 animate-spin"
                    aria-hidden
                  />
                )}

                Verify recovery
              </Button>
            )}

            {isActive && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="outline"
                    size="icon"
                    aria-label="More actions"
                  >
                    <MoreHorizontal className="size-4" />
                  </Button>
                </DropdownMenuTrigger>

                <DropdownMenuContent align="end">
                  {isOpen && (
                    <DropdownMenuItem
                      disabled={
                        pending ===
                        "acknowledge"
                      }
                      onSelect={() =>
                        void handleAcknowledge()
                      }
                    >
                      Acknowledge
                    </DropdownMenuItem>
                  )}

                  <DropdownMenuItem
                    onSelect={() =>
                      setConfirmResolveOpen(
                        true
                      )
                    }
                  >
                    Resolve without verifying
                  </DropdownMenuItem>

                  <DropdownMenuSeparator />

                  <DropdownMenuItem
                    onSelect={() =>
                      setFpOpen(true)
                    }
                  >
                    Mark as false positive
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </div>
        }
      />

      {/* Main body */}
      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        {/* Main content */}
        <Tabs
          value={activeTab}
          onValueChange={setActiveTab}
          className="min-w-0"
        >
          <TabsList>
            <TabsTrigger
              value="overview"
              className={TAB_TRIGGER}
            >
              Overview
            </TabsTrigger>

            <TabsTrigger
              value="evidence"
              className={TAB_TRIGGER}
            >
              Evidence
            </TabsTrigger>

            {investigation && (
              <TabsTrigger
                value="investigation"
                className={TAB_TRIGGER}
              >
                AI investigation
              </TabsTrigger>
            )}
          </TabsList>

          {/* ---------------------------------------------------------------- */}
          {/* Overview                                                         */}
          {/* ---------------------------------------------------------------- */}

          <TabsContent
            value="overview"
            className="space-y-6 pt-5"
          >
            {/* Recovery */}
            {showRecoveryPanel && (
              <Panel
                title="Recovery verification"
                description={
                  recoveryDescription
                }
                className="border-amber-500/20"
                action={
                  <Badge
                    variant="secondary"
                    className={
                      recoveryBadge.className
                    }
                  >
                    {recoveryBadge.text}
                  </Badge>
                }
              >
                <div className="space-y-5">
                  <div className="space-y-2">
                    <div className="flex items-center justify-between text-xs text-white/70">
                      <span>
                        Healthy executions
                      </span>

                      <span className="font-mono tabular-nums">
                        {healthyCount} /{" "}
                        {requiredCount}
                      </span>
                    </div>

                    <div
                      role="progressbar"
                      aria-label="Healthy executions observed"
                      aria-valuemin={0}
                      aria-valuemax={
                        requiredCount
                      }
                      aria-valuenow={Math.min(
                        healthyCount,
                        requiredCount
                      )}
                      className="flex items-center gap-1.5"
                    >
                      {Array.from({
                        length: Math.min(
                          requiredCount,
                          20
                        ),
                      }).map((_, i) => (
                        <div
                          key={i}
                          className={`h-1.5 flex-1 rounded-full ${
                            i < healthyCount
                              ? "bg-green-500"
                              : recoveryFailing
                              ? "bg-red-500/30"
                              : "bg-white/10"
                          }`}
                        />
                      ))}
                    </div>
                  </div>

                  <dl className="grid gap-4 border-t border-white/[0.06] pt-4 sm:grid-cols-3">
                    <Field
                      label="Current value"
                      mono
                    >
                      <span className="text-red-400">
                        {anomaly.actualValue}
                      </span>
                    </Field>

                    <Field
                      label="Baseline"
                      mono
                    >
                      {recoveryStatus
                        ?.baseline
                        ?.baseline ??
                        anomaly.expectedValue}
                    </Field>

                    <Field
                      label="Normal threshold"
                      mono
                    >
                      {recoveryStatus
                        ?.baseline
                        ?.threshold ??
                        "Within range"}
                    </Field>
                  </dl>

                  {isVerifying && (
                    <div className="flex flex-wrap items-center gap-3">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() =>
                          void handleVerify()
                        }
                        disabled={
                          pending === "verify"
                        }
                        className="gap-2"
                      >
                        <RefreshCw
                          className={`size-3.5 ${
                            pending ===
                            "verify"
                              ? "animate-spin"
                              : ""
                          }`}
                          aria-hidden
                        />

                        Restart verification
                      </Button>

                      <Button
                        size="sm"
                        onClick={() =>
                          setConfirmResolveOpen(
                            true
                          )
                        }
                        className="gap-2"
                      >
                        <CheckCircle2
                          className="size-3.5"
                          aria-hidden
                        />

                        Resolve now
                      </Button>
                    </div>
                  )}
                </div>
              </Panel>
            )}

            {/* Expected vs observed */}
            <Panel title="Expected vs observed">
              <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
                <Field
                  label="Expected"
                  mono
                >
                  {anomaly.expectedValue}
                </Field>

                <Field
                  label="Observed"
                  mono
                >
                  <span className="text-red-400">
                    {anomaly.actualValue}
                  </span>
                </Field>
              </dl>

              <dl className="mt-5 grid grid-cols-2 gap-x-6 gap-y-5 border-t border-white/[0.06] pt-5 sm:grid-cols-4">
                <Field
                  label="Deviation"
                  mono
                >
                  {formatSigma(
                    anomaly.deviation
                  )}
                </Field>

                <Field
                  label="Confidence"
                  mono
                >
                  {formatPercent(
                    anomaly.confidence
                  )}
                </Field>

                <Field
                  label="Affected executions"
                  mono
                >
                  {anomaly.affectedExecutions ??
                    "—"}
                </Field>

                <Field label="Metric">
                  {anomaly.metric || "—"}
                </Field>
              </dl>
            </Panel>

            {/* Timeline */}
            <Panel title="Timeline">
              <ol className="relative ml-1 space-y-5 border-l border-white/[0.08] pl-5">
                {timeline.map((event) => (
                  <li
                    key={event.key}
                    className="relative"
                  >
                    <span
                      className={`absolute -left-[25px] top-1.5 size-2 rounded-full ring-4 ring-[#0a0a0a] ${event.dot}`}
                      aria-hidden
                    />

                    <p className="text-sm text-white/90">
                      {event.label}
                    </p>

                    <p className="mt-0.5 font-mono text-xs tabular-nums text-white/44">
                      <FormattedDate
                        value={event.at}
                      />
                    </p>
                  </li>
                ))}
              </ol>
            </Panel>
          </TabsContent>

          {/* ---------------------------------------------------------------- */}
          {/* Evidence                                                         */}
          {/* ---------------------------------------------------------------- */}

          <TabsContent
            value="evidence"
            className="pt-5"
          >
            <Panel
              title="Detector evidence"
              description="Raw output from the statistical detector."
              action={
                detectorEvidence && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="gap-2"
                    onClick={() =>
                      void copyEvidence(
                        detectorEvidence
                      )
                    }
                  >
                    <Copy
                      className="size-3.5"
                      aria-hidden
                    />
                    Copy
                  </Button>
                )
              }
            >
              {detectorEvidence ? (
                <pre className="max-h-96 min-w-0 max-w-full overflow-auto whitespace-pre-wrap break-words rounded-lg bg-white/[0.03] p-4 font-mono text-xs leading-relaxed text-white/70 [overflow-wrap:anywhere]">
                  {JSON.stringify(
                    detectorEvidence,
                    null,
                    2
                  )}
                </pre>
              ) : (
                <p className="text-sm text-white/44">
                  The detector didn&apos;t
                  attach any evidence to this
                  anomaly.
                </p>
              )}
            </Panel>
          </TabsContent>

          {/* ---------------------------------------------------------------- */}
          {/* AI investigation                                                  */}
          {/* ---------------------------------------------------------------- */}

          {investigation && (
            <TabsContent
              value="investigation"
              className="pt-5"
            >
              <Panel
                title="AI investigation"
                description={
                  investigation.configured ? (
                    <>
                      Generated by{" "}
                      {investigation.model} on{" "}
                      <FormattedDate
                        value={
                          investigation.generatedAt
                        }
                        pattern="MMM d, HH:mm"
                      />
                    </>
                  ) : (
                    "No AI provider is configured. Add a provider key to enable investigations."
                  )
                }
                action={
                  <span className="whitespace-nowrap text-xs text-white/44">
                    {formatPercent(
                      investigation.confidence
                    )}{" "}
                    confidence
                  </span>
                }
              >
                <div className="space-y-7">
                  {/* Summary */}
                  {investigation.summary && (
                    <InvestigationSection title="Summary">
                      <p className="text-sm leading-relaxed text-white/70">
                        {investigation.summary}
                      </p>
                    </InvestigationSection>
                  )}

                  {/* Likely causes */}
                  {!!investigation
                    .likelyCauses
                    ?.length && (
                    <InvestigationSection title="Likely causes">
                      <ul className="divide-y divide-white/[0.06]">
                        {investigation.likelyCauses.map(
                          (cause, i) => (
                            <li
                              key={i}
                              className="py-3 first:pt-0 last:pb-0"
                            >
                              <p className="text-sm text-white/90">
                                {cause.cause}
                              </p>

                              <p className="mt-1 text-xs text-white/44">
                                {cause.category} ·{" "}
                                {formatPercent(
                                  cause.confidence
                                )}{" "}
                                confidence
                              </p>

                              {cause.uncertainty && (
                                <p className="mt-1 text-xs text-amber-400">
                                  Uncertainty:{" "}
                                  {
                                    cause.uncertainty
                                  }
                                </p>
                              )}
                            </li>
                          )
                        )}
                      </ul>
                    </InvestigationSection>
                  )}

                  {/* Supporting evidence */}
                  {!!investigation.evidence
                    ?.length && (
                    <InvestigationSection title="Supporting evidence">
                      <ul className="divide-y divide-white/[0.06]">
                        {investigation.evidence.map(
                          (item, i) => (
                            <li
                              key={i}
                              className="py-3 first:pt-0 last:pb-0"
                            >
                              <p className="text-sm text-white/90">
                                {item.type}:{" "}
                                {item.description}
                              </p>

                              <p className="mt-1 text-xs text-white/44">
                                Source:{" "}
                                {item.source}
                              </p>

                              {item.inference && (
                                <p className="mt-1 text-xs text-white/70">
                                  Inference:{" "}
                                  {
                                    item.inference
                                  }
                                </p>
                              )}
                            </li>
                          )
                        )}
                      </ul>
                    </InvestigationSection>
                  )}

                  {/* Impact */}
                  {investigation.impact && (
                    <InvestigationSection title="Impact">
                      <p className="text-sm leading-relaxed text-white/70">
                        {investigation.impact}
                      </p>
                    </InvestigationSection>
                  )}

                  {/* Recommended actions */}
                  {!!investigation
                    .recommendedActions
                    ?.length && (
                    <InvestigationSection title="Recommended actions">
                      <ul className="divide-y divide-white/[0.06]">
                        {investigation.recommendedActions.map(
                          (action, i) => (
                            <li
                              key={i}
                              className="py-3 first:pt-0 last:pb-0"
                            >
                              <p className="text-sm text-white/90">
                                {action.action}
                              </p>

                              <p className="mt-1 text-xs text-white/44">
                                {action.rationale}
                              </p>

                              <p className="mt-1 text-xs text-white/44">
                                Risk:{" "}
                                {action.risk}{" "}
                                · Effort:{" "}
                                {action.effort}
                              </p>
                            </li>
                          )
                        )}
                      </ul>
                    </InvestigationSection>
                  )}
                </div>
              </Panel>
            </TabsContent>
          )}
        </Tabs>

        {/* ------------------------------------------------------------------ */}
        {/* Sidebar                                                             */}
        {/* ------------------------------------------------------------------ */}

        <aside className="min-w-0">
          <Panel title="Details">
            <dl className="space-y-4">
              <Field label="Workflow">
                <Link
                  href={`/workflows/${anomaly.workflowId}`}
                  className="transition-colors hover:underline"
                >
                  {anomaly.workflowName}
                </Link>
              </Field>

              {anomaly.nodeId && (
                <Field label="Node">
                  <code className="break-all font-mono text-xs text-white/70">
                    {anomaly.nodeId}
                  </code>

                  {anomaly.nodeType && (
                    <span className="mt-0.5 block text-xs text-white/44">
                      {anomaly.nodeType}
                    </span>
                  )}
                </Field>
              )}

              {anomaly.executionId && (
                <Field label="Triggering execution">
                  <Link
                    href={`/executions/${anomaly.executionId}`}
                    className="font-mono text-xs transition-colors hover:underline"
                  >
                    {anomaly.executionId.slice(
                      0,
                      8
                    )}
                  </Link>
                </Field>
              )}

              <Field
                label="Anomaly ID"
                mono
              >
                <span className="break-all text-xs text-white/70">
                  {anomaly.id}
                </span>
              </Field>

              {anomaly.dedupKey && (
                <Field
                  label="Dedup key"
                  mono
                >
                  <span className="break-all text-xs text-white/70">
                    {anomaly.dedupKey}
                  </span>
                </Field>
              )}
            </dl>
          </Panel>
        </aside>
      </div>

      {/* -------------------------------------------------------------------- */}
      {/* Resolve confirmation                                                 */}
      {/* -------------------------------------------------------------------- */}

      <Dialog
        open={confirmResolveOpen}
        onOpenChange={(open) => {
          if (
            !open &&
            pending !== "resolve"
          ) {
            setConfirmResolveOpen(false);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              Resolve without verifying?
            </DialogTitle>

            <DialogDescription>
              This closes the anomaly without
              confirming the metric is back
              within its baseline. If the
              problem is still happening, it
              will be detected again as a new
              anomaly.
            </DialogDescription>
          </DialogHeader>

          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              disabled={
                pending === "resolve"
              }
              onClick={() =>
                setConfirmResolveOpen(false)
              }
            >
              Cancel
            </Button>

            <Button
              type="button"
              disabled={
                pending === "resolve"
              }
              onClick={() =>
                void handleResolve()
              }
            >
              {pending === "resolve"
                ? "Resolving…"
                : "Resolve anomaly"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* -------------------------------------------------------------------- */}
      {/* False positive                                                       */}
      {/* -------------------------------------------------------------------- */}

      <Dialog
        open={fpOpen}
        onOpenChange={(open) => {
          if (
            !open &&
            !fpSubmitting
          ) {
            closeFalsePositive();
          }
        }}
      >
        <DialogContent>
          <form
            onSubmit={submitFalsePositive}
            className="space-y-4"
          >
            <DialogHeader>
              <DialogTitle>
                Mark as false positive
              </DialogTitle>

              <DialogDescription>
                Explain why this anomaly
                isn&apos;t a real issue. The
                reason is saved to the audit
                trail and helps the detector
                avoid repeating it.
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-2">
              <Label htmlFor="fp-reason">
                Reason
              </Label>

              <Input
                id="fp-reason"
                autoFocus
                value={fpReason}
                maxLength={
                  REASON_MAX_LENGTH
                }
                onChange={(e) =>
                  setFpReason(
                    e.target.value
                  )
                }
                placeholder="Expected during deploy window"
              />
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="ghost"
                disabled={fpSubmitting}
                onClick={
                  closeFalsePositive
                }
              >
                Cancel
              </Button>

              <Button
                type="submit"
                disabled={
                  !fpReason.trim() ||
                  fpSubmitting
                }
              >
                {fpSubmitting
                  ? "Saving…"
                  : "Mark as false positive"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </PageContainer>
  );
}