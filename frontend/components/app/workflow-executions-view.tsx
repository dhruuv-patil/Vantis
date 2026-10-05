"use client";

import * as React from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ChevronRight,
  Play,
  RotateCw,
  Search,
  X,
} from "lucide-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { fetchExecutions, getErrorMessage } from "@/lib/api";
import type { ExecutionStatus, ExecutionSummary } from "@/types";
import { formatDuration, formatRelativeTime, isLiveStatus } from "@/lib/format";
import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/* ================================================================
   Config
   ================================================================ */

type StatusFilter = ExecutionStatus | "ALL";

const STATUS_FILTERS: { label: string; value: StatusFilter }[] = [
  { label: "All statuses", value: "ALL" },
  { label: "Running", value: "RUNNING" },
  { label: "Waiting", value: "WAITING" },
  { label: "Succeeded", value: "SUCCEEDED" },
  { label: "Failed", value: "FAILED" },
  { label: "Canceled", value: "CANCELED" },
];

const STATUS_CONFIG: Record<
  string,
  { label: string; dot: string; pill: string }
> = {
  SUCCEEDED: {
    label: "Succeeded",
    dot: "bg-emerald-400",
    pill: "border-emerald-400/20 bg-emerald-400/[0.07] text-emerald-300",
  },
  FAILED: {
    label: "Failed",
    dot: "bg-red-400",
    pill: "border-red-400/20 bg-red-400/[0.07] text-red-300",
  },
  RUNNING: {
    label: "Running",
    dot: "bg-amber-400",
    pill: "border-amber-400/20 bg-amber-400/[0.07] text-amber-300",
  },
  WAITING: {
    label: "Waiting",
    dot: "bg-sky-400",
    pill: "border-sky-400/20 bg-sky-400/[0.07] text-sky-300",
  },
  QUEUED: {
    label: "Queued",
    dot: "bg-violet-400",
    pill: "border-violet-400/20 bg-violet-400/[0.07] text-violet-300",
  },
  CANCELED: {
    label: "Canceled",
    dot: "bg-white/30",
    pill: "border-white/[0.10] bg-white/[0.035] text-white/50",
  },
};

const FALLBACK_STATUS = {
  label: "Unknown",
  dot: "bg-white/30",
  pill: "border-white/[0.10] bg-white/[0.035] text-white/50",
};

function getStatus(status: string) {
  return (
    STATUS_CONFIG[status.toUpperCase()] ?? {
      ...FALLBACK_STATUS,
      label: humanize(status),
    }
  );
}

function humanize(value: string) {
  const text = value.replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/* ================================================================
   View
   ================================================================ */

interface WorkflowExecutionsViewProps {
  workflowId: string;
}

export function WorkflowExecutionsView({
  workflowId,
}: WorkflowExecutionsViewProps) {
  const [status, setStatus] = React.useState<StatusFilter>("ALL");
  const [search, setSearch] = React.useState("");

  const query = useQuery({
    queryKey: ["executions", "workflow", workflowId, { status }],
    queryFn: () =>
      fetchExecutions({
        workflowId,
        status: status === "ALL" ? undefined : status,
        limit: 100,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: (q) =>
      (q.state.data?.executions ?? []).some((e) => isLiveStatus(e.status))
        ? 4000
        : false,
  });

  const executions = React.useMemo(
    () => query.data?.executions ?? [],
    [query.data],
  );

  const visibleExecutions = React.useMemo(() => {
    const term = search.trim().toLowerCase();

    return executions
      .filter((execution) => {
        if (status !== "ALL" && execution.status.toUpperCase() !== status) {
          return false;
        }
        if (!term) return true;

        const haystack = [
          execution.workflowName,
          execution.id,
          execution.triggerType,
          execution.error,
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();

        return haystack.includes(term);
      })
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  }, [executions, status, search]);

  const stats = React.useMemo(() => {
    const upper = (e: ExecutionSummary) => e.status.toUpperCase();

    const active = executions.filter((e) => isLiveStatus(e.status)).length;
    const succeeded = executions.filter((e) => upper(e) === "SUCCEEDED").length;
    const failed = executions.filter((e) => upper(e) === "FAILED").length;
    const completed = executions.filter((e) =>
      ["SUCCEEDED", "FAILED", "CANCELED"].includes(upper(e)),
    );

    const timed = completed.filter((e) => e.durationMs != null);
    const avgDuration =
      timed.length > 0
        ? timed.reduce((sum, e) => sum + (e.durationMs ?? 0), 0) / timed.length
        : null;

    return {
      total: executions.length,
      active,
      failed,
      avgDuration,
      successRate:
        completed.length > 0
          ? Math.round((succeeded / completed.length) * 1000) / 10
          : null,
    };
  }, [executions]);

  const hasFilters = status !== "ALL" || search.trim().length > 0;
  const loading = query.isPending;

  function clearFilters() {
    setStatus("ALL");
    setSearch("");
  }

  const statItems: { label: string; value: string; tone: Tone }[] = [
    { label: "Total runs", value: stats.total.toLocaleString(), tone: "neutral" },
    {
      label: "Success rate",
      value: stats.successRate == null ? "—" : `${stats.successRate}%`,
      tone:
        stats.successRate == null
          ? "neutral"
          : stats.successRate >= 90
            ? "success"
            : "warning",
    },
    {
      label: "Failed",
      value: stats.failed.toLocaleString(),
      tone: stats.failed > 0 ? "danger" : "neutral",
    },
    {
      label: "Avg duration",
      value: formatDuration(stats.avgDuration),
      tone: "neutral",
    },
  ];

  return (
    <div className="space-y-5 px-4 pb-8 pt-5 lg:px-5">
      {/* Stats */}
      <div
        className={cn(
          "grid grid-cols-2 overflow-hidden rounded-xl border border-white/[0.07]",
          "bg-[#0a0a0a] sm:grid-cols-4",
        )}
      >
        {statItems.map((item, index) => (
          <div
            key={item.label}
            className={cn(
              "px-5 py-4",
              index % 2 === 1 && "border-l border-white/[0.06]",
              index > 1 && "border-t border-white/[0.06] sm:border-t-0",
              index > 0 && "sm:border-l sm:border-white/[0.06]",
            )}
          >
            <div className="flex items-center gap-2">
              <span className={cn("size-1.5 rounded-full", TONE_DOT[item.tone])} />
              <p className="text-xs text-white/45">{item.label}</p>
            </div>
            <p className="mt-2 text-xl font-semibold tabular-nums tracking-[-0.02em] text-white">
              {item.value}
            </p>
          </div>
        ))}
      </div>

      {/* Toolbar */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 className="text-[15px] font-semibold tracking-[-0.01em] text-white">
            Executions
          </h2>
          <p className="mt-1 flex items-center gap-2 text-xs text-white/40">
            {loading
              ? "Loading executions…"
              : hasFilters
                ? `${visibleExecutions.length} matching ${
                    visibleExecutions.length === 1 ? "run" : "runs"
                  }`
                : executions.length === 0
                  ? "No executions yet"
                  : `${visibleExecutions.length} shown, newest first`}
            {stats.active > 0 && (
              <span className="inline-flex items-center gap-1.5 text-amber-300/90">
                <span className="size-1.5 animate-pulse rounded-full bg-amber-400" />
                {stats.active} live
              </span>
            )}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative w-full sm:w-64">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-white/40" />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search ID, trigger, error…"
              aria-label="Search executions"
              className={cn(
                "h-9 border-white/[0.08] bg-white/[0.025] pl-9 pr-8 text-[13px]",
                "placeholder:text-white/45",
                "focus-visible:border-white/[0.16] focus-visible:ring-0",
                "focus-visible:ring-offset-0",
              )}
            />
            {search && (
              <button
                type="button"
                onClick={() => setSearch("")}
                aria-label="Clear search"
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-white/40 hover:text-white"
              >
                <X className="size-3.5" />
              </button>
            )}
          </div>

          <Select
            value={status}
            onValueChange={(value) => setStatus(value as StatusFilter)}
          >
            <SelectTrigger
              aria-label="Filter by status"
              className={cn(
                "h-9 w-[160px] border-white/[0.08] bg-white/[0.025] text-[13px]",
                "focus:ring-0 focus:ring-offset-0",
              )}
            >
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent className="border-white/[0.08] bg-[#0f0f0f]">
              {STATUS_FILTERS.map((filter) => (
                <SelectItem key={filter.value} value={filter.value}>
                  {filter.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {hasFilters && (
            <Button
              variant="ghost"
              size="sm"
              onClick={clearFilters}
              className="h-9 px-3 text-white/50 hover:bg-white/[0.06] hover:text-white"
            >
              Clear
            </Button>
          )}
        </div>
      </div>

      {/* Feed */}
      {query.isError && !query.data ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : loading ? (
        <ExecutionListSkeleton />
      ) : visibleExecutions.length === 0 ? (
        <EmptyState
          filtered={hasFilters}
          onClear={hasFilters ? clearFilters : undefined}
        />
      ) : (
        <ul
          className={cn(
            "overflow-hidden rounded-xl border border-white/[0.07]",
            "divide-y divide-white/[0.06] bg-[#0a0a0a]",
          )}
        >
          {visibleExecutions.map((execution) => (
            <ExecutionRow key={execution.id} execution={execution} />
          ))}
        </ul>
      )}
    </div>
  );
}

/* ================================================================
   Stats tones
   ================================================================ */

type Tone = "neutral" | "success" | "danger" | "warning";

const TONE_DOT: Record<Tone, string> = {
  neutral: "bg-white/25",
  success: "bg-emerald-400",
  danger: "bg-red-400",
  warning: "bg-amber-400",
};

/* ================================================================
   Row
   ================================================================ */

function ExecutionRow({ execution }: { execution: ExecutionSummary }) {
  const live = isLiveStatus(execution.status);
  const config = getStatus(execution.status);

  return (
    <li>
      <Link
        href={`/executions/${execution.id}`}
        className={cn(
          "group flex min-w-0 items-center gap-4 px-5 py-4",
          "transition-colors hover:bg-white/[0.02]",
          "focus-visible:bg-white/[0.03] focus-visible:outline-none",
        )}
      >
        <span
          aria-hidden
          className={cn(
            "size-2 shrink-0 rounded-full",
            config.dot,
            live && "animate-pulse",
          )}
        />

        <span
          className={cn(
            "inline-flex w-[88px] shrink-0 items-center justify-center rounded-md border",
            "px-2 py-1 text-[11px] font-medium leading-none",
            config.pill,
          )}
        >
          {config.label}
        </span>

        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-white/90">
            {execution.workflowName ?? "Untitled workflow"}
          </span>

          <span className="mt-1 flex min-w-0 items-center gap-2 text-xs text-white/40">
            <span className="shrink-0 font-mono">
              v{execution.versionNumber}
            </span>
            <span aria-hidden className="text-white/20">/</span>
            <span className="shrink-0">
              {humanize(execution.triggerType)} trigger
            </span>
            {execution.error && (
              <>
                <span aria-hidden className="text-white/20">/</span>
                <span className="truncate text-red-300/80">
                  {execution.error}
                </span>
              </>
            )}
          </span>
        </span>

        <span className="hidden shrink-0 text-right text-xs sm:block">
          <span className="block text-white/60">
            {formatRelativeTime(execution.createdAt)}
          </span>
          <span className="mt-1 block font-mono text-white/35">
            {formatDuration(execution.durationMs)}
          </span>
        </span>

        <ChevronRight className="size-4 shrink-0 text-white/20 transition-colors group-hover:text-white/50" />
      </Link>
    </li>
  );
}

/* ================================================================
   States
   ================================================================ */

function ExecutionListSkeleton() {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-white/[0.07]",
        "divide-y divide-white/[0.06] bg-[#0a0a0a]",
      )}
    >
      {Array.from({ length: 6 }).map((_, index) => (
        <div key={index} className="flex items-center gap-4 px-5 py-4">
          <Skeleton className="size-2 rounded-full bg-white/[0.06]" />
          <Skeleton className="h-6 w-[88px] bg-white/[0.06]" />
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <Skeleton className="h-3.5 w-48 max-w-full bg-white/[0.06]" />
            <Skeleton className="h-3 w-64 max-w-full bg-white/[0.04]" />
          </div>
          <div className="hidden flex-col items-end gap-2 sm:flex">
            <Skeleton className="h-3 w-16 bg-white/[0.06]" />
            <Skeleton className="h-3 w-12 bg-white/[0.04]" />
          </div>
        </div>
      ))}
    </div>
  );
}

function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div
      role="alert"
      className={cn(
        "flex items-center justify-between gap-4 rounded-xl border",
        "border-red-400/20 bg-red-400/[0.05] px-4 py-3",
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <AlertTriangle className="size-4 shrink-0 text-red-300" />
        <div className="min-w-0">
          <p className="text-sm text-red-200">Unable to load executions.</p>
          <p className="mt-0.5 truncate text-xs text-white/40">{message}</p>
        </div>
      </div>
      <Button
        variant="outline"
        size="sm"
        onClick={onRetry}
        className="h-8 shrink-0 gap-1.5 border-white/[0.12] bg-transparent text-white hover:bg-white/[0.08] hover:text-white"
      >
        <RotateCw className="size-3.5" />
        Retry
      </Button>
    </div>
  );
}

function EmptyState({
  filtered,
  onClear,
}: {
  filtered: boolean;
  onClear?: () => void;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border",
        "border-white/[0.07] bg-[#0a0a0a] px-6 py-16 text-center",
      )}
    >
      <div
        className={cn(
          "mb-4 flex size-12 items-center justify-center rounded-xl",
          "border border-white/[0.08] bg-white/[0.03]",
        )}
      >
        {filtered ? (
          <Search className="size-5 text-white/40" />
        ) : (
          <Play className="size-5 text-white/40" />
        )}
      </div>

      <p className="text-sm font-medium text-white/90">
        {filtered ? "No matching executions" : "No executions yet"}
      </p>
      <p className="mt-1 max-w-sm text-xs leading-relaxed text-white/40">
        {filtered
          ? "Try a different status or search term."
          : "Publish this workflow and run it to see executions here."}
      </p>

      {onClear && (
        <Button
          variant="ghost"
          size="sm"
          onClick={onClear}
          className="mt-4 h-8 text-white/60 hover:bg-white/[0.06] hover:text-white"
        >
          Clear filters
        </Button>
      )}
    </div>
  );
}