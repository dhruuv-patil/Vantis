"use client";

import * as React from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ExternalLink,
  Loader2,
  MoreHorizontal,
  RotateCw,
  Search,
  ShieldCheck,
  X,
} from "lucide-react";
import { toast } from "sonner";
import { format } from "date-fns";

import {
  fetchAnomalies,
  acknowledgeAnomaly,
  resolveAnomaly,
  markAnomalyFalsePositive,
} from "@/lib/api";
import type {
  AnomaliesResponse,
  AnomalySummary,
  AnomalyStatus,
  AnomalySeverity,
} from "@/types";
import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/* ================================================================
   Config
   ================================================================ */

const SEVERITY_CONFIG: Record<
  AnomalySeverity,
  { label: string; bar: string; dot: string; text: string }
> = {
  LOW: {
    label: "Low",
    bar: "bg-emerald-400/60",
    dot: "bg-emerald-400",
    text: "text-emerald-300",
  },
  MEDIUM: {
    label: "Medium",
    bar: "bg-amber-400/60",
    dot: "bg-amber-400",
    text: "text-amber-300",
  },
  HIGH: {
    label: "High",
    bar: "bg-orange-400/70",
    dot: "bg-orange-400",
    text: "text-orange-300",
  },
  CRITICAL: {
    label: "Critical",
    bar: "bg-red-400/80",
    dot: "bg-red-400",
    text: "text-red-300",
  },
};

const STATUS_CONFIG: Record<AnomalyStatus, { label: string; className: string }> =
  {
    OPEN: {
      label: "Open",
      className: "border-sky-400/20 bg-sky-400/[0.07] text-sky-300",
    },
    ACKNOWLEDGED: {
      label: "Acknowledged",
      className: "border-violet-400/20 bg-violet-400/[0.07] text-violet-300",
    },
    VERIFYING_RECOVERY: {
      label: "Verifying recovery",
      className: "border-amber-400/20 bg-amber-400/[0.07] text-amber-300",
    },
    RESOLVED: {
      label: "Resolved",
      className:
        "border-emerald-400/20 bg-emerald-400/[0.07] text-emerald-300",
    },
    FALSE_POSITIVE: {
      label: "False positive",
      className: "border-white/[0.10] bg-white/[0.035] text-white/50",
    },
  };

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: "ALL", label: "All statuses" },
  { value: "OPEN", label: "Open" },
  { value: "ACKNOWLEDGED", label: "Acknowledged" },
  { value: "VERIFYING_RECOVERY", label: "Verifying recovery" },
  { value: "RESOLVED", label: "Resolved" },
  { value: "FALSE_POSITIVE", label: "False positive" },
];

function humanize(value: string) {
  const text = value.replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/* ================================================================
   Small pieces
   ================================================================ */

function StatusBadge({ status }: { status: AnomalyStatus }) {
  const config = STATUS_CONFIG[status];
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center rounded-md border",
        "px-2 py-1 text-[11px] font-medium leading-none",
        config.className,
      )}
    >
      {config.label}
    </span>
  );
}

function MetaChip({ children }: { children: React.ReactNode }) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-md border border-white/[0.08]",
        "bg-white/[0.025] px-2 py-1 text-[11px] leading-none text-white/60",
      )}
    >
      {children}
    </span>
  );
}

function RowSkeleton() {
  return (
    <div className="flex items-center gap-4 px-5 py-4">
      <Skeleton className="h-10 w-1 rounded-full bg-white/[0.06]" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-4 w-56 bg-white/[0.06]" />
        <Skeleton className="h-3 w-80 max-w-full bg-white/[0.04]" />
      </div>
      <Skeleton className="h-6 w-20 bg-white/[0.06]" />
    </div>
  );
}

/* ================================================================
   View
   ================================================================ */

interface WorkflowAnomaliesViewProps {
  workflowId: string;
}

export function WorkflowAnomaliesView({
  workflowId,
}: WorkflowAnomaliesViewProps) {
  const [anomalies, setAnomalies] = React.useState<AnomalySummary[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [statusFilter, setStatusFilter] = React.useState("ALL");
  const [searchTerm, setSearchTerm] = React.useState("");
  const [falsePositiveId, setFalsePositiveId] = React.useState<string | null>(
    null,
  );
  const [falsePositiveReason, setFalsePositiveReason] = React.useState("");
  const [actionPending, setActionPending] = React.useState(false);

  const load = React.useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const response: AnomaliesResponse = await fetchAnomalies({
        workflowId,
        status:
          statusFilter === "ALL" ? undefined : (statusFilter as AnomalyStatus),
      });
      setAnomalies(response.anomalies);
    } catch (err) {
      console.error(err);
      setError("Failed to load anomalies.");
    } finally {
      setLoading(false);
    }
  }, [workflowId, statusFilter]);

  React.useEffect(() => {
    void load();
  }, [load]);

  async function runAction(
    action: (id: string) => Promise<unknown>,
    id: string,
  ) {
    setActionPending(true);
    try {
      await action(id);
      toast.success("Anomaly updated.");
      await load();
    } catch (err) {
      console.error(err);
      toast.error("Could not update the anomaly.");
    } finally {
      setActionPending(false);
    }
  }

  function closeFalsePositiveDialog() {
    setFalsePositiveId(null);
    setFalsePositiveReason("");
  }

  async function submitFalsePositive() {
    if (!falsePositiveId) return;
    const reason = falsePositiveReason.trim();
    if (!reason) return;

    setActionPending(true);
    try {
      await markAnomalyFalsePositive(falsePositiveId, reason);
      toast.success("Anomaly marked as false positive.");
      closeFalsePositiveDialog();
      await load();
    } catch (err) {
      console.error(err);
      toast.error("Could not update the anomaly.");
    } finally {
      setActionPending(false);
    }
  }

  const filtered = React.useMemo(() => {
    const search = searchTerm.trim().toLowerCase();
    if (!search) return anomalies;

    return anomalies.filter(
      (a) =>
        a.workflowName.toLowerCase().includes(search) ||
        a.nodeId?.toLowerCase().includes(search) ||
        a.type.toLowerCase().includes(search) ||
        a.metric?.toLowerCase().includes(search),
    );
  }, [anomalies, searchTerm]);

  const openCount = anomalies.filter((a) => a.status === "OPEN").length;
  const hasFilters = statusFilter !== "ALL" || searchTerm.trim() !== "";

  return (
    <div className="space-y-5 px-4 pb-8 pt-5 lg:px-5">
      {/* Toolbar */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 className="text-[15px] font-semibold tracking-[-0.01em] text-white">
            Anomalies
          </h2>
          <p className="mt-1 text-xs text-white/40">
            {loading
              ? "Loading anomalies…"
              : `${filtered.length} of ${anomalies.length} shown, ${openCount} open`}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative w-full sm:w-64">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-white/40" />
            <Input
              placeholder="Search anomalies…"
              aria-label="Search anomalies"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className={cn(
                "h-9 border-white/[0.08] bg-white/[0.025] pl-9 pr-8 text-[13px]",
                "placeholder:text-white/45",
                "focus-visible:border-white/[0.16] focus-visible:ring-0",
                "focus-visible:ring-offset-0",
              )}
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => setSearchTerm("")}
                aria-label="Clear search"
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-white/40 hover:text-white"
              >
                <X className="size-3.5" />
              </button>
            )}
          </div>

          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger
              aria-label="Filter by status"
              className={cn(
                "h-9 w-[180px] border-white/[0.08] bg-white/[0.025] text-[13px]",
                "focus:ring-0 focus:ring-offset-0",
              )}
            >
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent className="border-white/[0.08] bg-[#0f0f0f]">
              {STATUS_FILTERS.map((f) => (
                <SelectItem key={f.value} value={f.value}>
                  {f.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div
          role="alert"
          className={cn(
            "flex items-center justify-between gap-4 rounded-xl border",
            "border-red-400/20 bg-red-400/[0.05] px-4 py-3",
          )}
        >
          <div className="flex items-center gap-3">
            <AlertTriangle className="size-4 shrink-0 text-red-300" />
            <p className="text-sm text-red-200">{error}</p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void load()}
            className="h-8 gap-1.5 border-white/[0.12] bg-transparent text-white hover:bg-white/[0.08] hover:text-white"
          >
            <RotateCw className="size-3.5" />
            Retry
          </Button>
        </div>
      )}

      {/* List */}
      <div
        className={cn(
          "overflow-hidden rounded-xl border border-white/[0.07]",
          "bg-[#0a0a0a]",
        )}
      >
        {loading ? (
          <div className="divide-y divide-white/[0.06]">
            {Array.from({ length: 4 }).map((_, i) => (
              <RowSkeleton key={i} />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
            <div
              className={cn(
                "mb-4 flex size-12 items-center justify-center rounded-xl",
                "border border-white/[0.08] bg-white/[0.03]",
              )}
            >
              {hasFilters ? (
                <Search className="size-5 text-white/40" />
              ) : (
                <ShieldCheck className="size-5 text-emerald-300/80" />
              )}
            </div>
            <p className="text-sm font-medium text-white/90">
              {hasFilters ? "No anomalies match your filters" : "No anomalies detected"}
            </p>
            <p className="mt-1 max-w-sm text-xs leading-relaxed text-white/40">
              {hasFilters
                ? "Try a different search term or status."
                : "Anomalies appear here once enough runs have been recorded to build a baseline."}
            </p>
            {hasFilters && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearchTerm("");
                  setStatusFilter("ALL");
                }}
                className="mt-4 h-8 text-white/60 hover:bg-white/[0.06] hover:text-white"
              >
                Clear filters
              </Button>
            )}
          </div>
        ) : (
          <ul className="divide-y divide-white/[0.06]">
            {filtered.map((anomaly) => (
              <AnomalyRow
                key={anomaly.id}
                anomaly={anomaly}
                actionPending={actionPending}
                onAction={runAction}
                onFalsePositive={() => setFalsePositiveId(anomaly.id)}
              />
            ))}
          </ul>
        )}
      </div>

      {/* False positive dialog */}
      <Dialog
        open={falsePositiveId !== null}
        onOpenChange={(open) => {
          if (!open) closeFalsePositiveDialog();
        }}
      >
        <DialogContent className="border-white/[0.08] bg-[#0c0c0c]">
          <DialogHeader>
            <DialogTitle>Mark as false positive</DialogTitle>
            <DialogDescription>
              Record why this anomaly is not a real issue. The reason is kept
              for the audit trail and helps the detector avoid repeating it.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-2">
            <Label htmlFor="fp-reason" className="text-[13px] text-white/70">
              Reason
            </Label>
            <Input
              id="fp-reason"
              autoFocus
              value={falsePositiveReason}
              onChange={(e) => setFalsePositiveReason(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && falsePositiveReason.trim()) {
                  void submitFalsePositive();
                }
              }}
              placeholder="e.g. Expected during deploy window"
              className="h-9 border-white/[0.08] bg-white/[0.025] text-[13px] focus-visible:ring-0"
            />
          </div>

          <DialogFooter className="gap-2 sm:gap-2">
            <Button
              type="button"
              variant="ghost"
              onClick={closeFalsePositiveDialog}
              className="text-white/60 hover:bg-white/[0.06] hover:text-white"
            >
              Cancel
            </Button>
            <Button
              type="button"
              onClick={() => void submitFalsePositive()}
              disabled={!falsePositiveReason.trim() || actionPending}
              className="gap-1.5 bg-white text-black hover:bg-white/90 disabled:bg-white/[0.08] disabled:text-white/25"
            >
              {actionPending && <Loader2 className="size-3.5 animate-spin" />}
              {actionPending ? "Saving" : "Mark as false positive"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/* ================================================================
   Row
   ================================================================ */

function AnomalyRow({
  anomaly,
  actionPending,
  onAction,
  onFalsePositive,
}: {
  anomaly: AnomalySummary;
  actionPending: boolean;
  onAction: (action: (id: string) => Promise<unknown>, id: string) => void;
  onFalsePositive: () => void;
}) {
  const severity = SEVERITY_CONFIG[anomaly.severity];
  const hasMetric = anomaly.metric !== null;
  const canAct = anomaly.status === "OPEN" || anomaly.status === "ACKNOWLEDGED";

  return (
    <li className="group relative transition-colors hover:bg-white/[0.02]">
      {/* Severity accent */}
      <span
        aria-hidden
        className={cn("absolute inset-y-0 left-0 w-[2px]", severity.bar)}
      />

      <div className="flex items-start gap-4 py-4 pl-5 pr-4">
        <div className="min-w-0 flex-1 space-y-2">
          {/* Title row */}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <span
              className={cn(
                "inline-flex shrink-0 items-center gap-1.5 text-xs font-medium",
                severity.text,
              )}
            >
              <span className={cn("size-1.5 rounded-full", severity.dot)} />
              {severity.label}
            </span>

            <span className="text-sm font-medium text-white/90">
              {humanize(anomaly.type)} anomaly
            </span>

            <StatusBadge status={anomaly.status} />
          </div>

          {/* Context row */}
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-white/40">
            <Link
              href={`/workflows/${anomaly.workflowId}`}
              className="max-w-[260px] truncate text-white/60 hover:text-white hover:underline"
            >
              {anomaly.workflowName}
            </Link>

            {anomaly.nodeId && (
              <>
                <span aria-hidden className="text-white/20">/</span>
                <span className="font-mono">{anomaly.nodeId}</span>
              </>
            )}

            {anomaly.nodeType && (
              <>
                <span aria-hidden className="text-white/20">/</span>
                <span>{humanize(anomaly.nodeType)}</span>
              </>
            )}

            <span aria-hidden className="text-white/20">/</span>
            <time dateTime={anomaly.detectedAt}>
              {format(new Date(anomaly.detectedAt), "MMM d, HH:mm")}
            </time>

            <span aria-hidden className="text-white/20">/</span>
            <span className="font-mono text-white/45">
              {anomaly.id.slice(0, 8)}
            </span>
          </div>

          {/* Metric */}
          {hasMetric && (
            <div className="flex flex-wrap items-center gap-2 pt-0.5">
              <MetaChip>
                <span className="font-mono">
                  {anomaly.metric}: {anomaly.expectedValue} → {anomaly.actualValue}
                </span>
              </MetaChip>
              <MetaChip>
                {anomaly.deviation > 0 ? "+" : ""}
                {anomaly.deviation.toFixed(1)}σ
              </MetaChip>
              <MetaChip>{Math.round(anomaly.confidence * 100)}% confidence</MetaChip>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="flex shrink-0 items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Actions for anomaly ${anomaly.id.slice(0, 8)}`}
                className="size-8 text-white/40 hover:bg-white/[0.06] hover:text-white"
              >
                <MoreHorizontal className="size-4" />
              </Button>
            </DropdownMenuTrigger>

            <DropdownMenuContent
              align="end"
              className="w-48 border-white/[0.08] bg-[#0f0f0f]"
            >
              <DropdownMenuLabel className="text-xs font-normal text-white/40">
                Anomaly {anomaly.id.slice(0, 8)}
              </DropdownMenuLabel>
              <DropdownMenuSeparator className="bg-white/[0.07]" />

              <DropdownMenuItem asChild>
                <Link
                  href={`/reliability/${anomaly.id}`}
                  className="flex items-center gap-2"
                >
                  <ExternalLink className="size-3.5" />
                  View details
                </Link>
              </DropdownMenuItem>

              {canAct && <DropdownMenuSeparator className="bg-white/[0.07]" />}

              {anomaly.status === "OPEN" && (
                <DropdownMenuItem
                  onClick={() => onAction(acknowledgeAnomaly, anomaly.id)}
                  disabled={actionPending}
                >
                  Acknowledge
                </DropdownMenuItem>
              )}

              {canAct && (
                <DropdownMenuItem
                  onClick={() => onAction(resolveAnomaly, anomaly.id)}
                  disabled={actionPending}
                >
                  Resolve
                </DropdownMenuItem>
              )}

              {anomaly.status === "OPEN" && (
                <DropdownMenuItem
                  onClick={onFalsePositive}
                  disabled={actionPending}
                >
                  Mark false positive
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
    </li>
  );
}