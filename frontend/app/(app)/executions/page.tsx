"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import {
  Activity,
  ArrowUpRight,
  ChevronDown,
  ExternalLink,
  Search,
  X,
} from "lucide-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  fetchExecutionEvents,
  fetchExecutions,
  getErrorMessage,
} from "@/lib/api";
import type {
  ExecutionEvent,
  ExecutionStatus,
} from "@/types";
import {
  formatDuration,
  formatRelativeTime,
  isLiveStatus,
} from "@/lib/format";
import { getHealthLabel, getHealthTone } from "@/lib/status";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  EmptyState,
  ErrorState,
  KpiStrip,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  StatusBadge,
  TableSkeleton,
  Toolbar,
} from "@/components/app/page-primitives";
import {
  Reveal,
  StaggerGroup,
  StaggerItem,
} from "@/components/motion/motion-primitives";

type SourceFilter = "ALL" | "INTERNAL" | "EXTERNAL";

const STATUS_FILTERS: {
  label: string;
  value: ExecutionStatus | "ALL";
}[] = [
  { label: "All statuses", value: "ALL" },
  { label: "Running", value: "RUNNING" },
  { label: "Waiting", value: "WAITING" },
  { label: "Succeeded", value: "SUCCEEDED" },
  { label: "Failed", value: "FAILED" },
  { label: "Canceled", value: "CANCELED" },
];

interface MonitoredExecution {
  key: string;
  source: string;
  workflowExternalId: string;
  workflowName: string;
  executionExternalId: string;
  status: ExecutionStatus | string;
  events: ExecutionEvent[];
  nodeCount: number;
  latestEvent: ExecutionEvent;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
}

type UnifiedExecution =
  | {
      kind: "INTERNAL";
      id: string;
      workflowName: string;
      status: ExecutionStatus;
      createdAt: string;
      durationMs: number | null;
      versionNumber: number;
      triggerType: string;
      error: string | null;
      searchText: string;
    }
  | {
      kind: "EXTERNAL";
      key: string;
      workflowName: string;
      status: string;
      createdAt: string;
      durationMs: number | null;
      source: string;
      executionExternalId: string;
      workflowExternalId: string;
      nodeCount: number;
      searchText: string;
    };

export default function ExecutionsPage() {
  const [source, setSource] = useState<SourceFilter>("ALL");
  const [status, setStatus] = useState<ExecutionStatus | "ALL">("ALL");
  const [search, setSearch] = useState("");
  const [pageSize, setPageSize] = useState(25);

  const query = useQuery({
    queryKey: ["executions", { status }],
    queryFn: () =>
      fetchExecutions({
        status: status === "ALL" ? undefined : status,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: (q) =>
      (q.state.data?.executions ?? []).some((e) => isLiveStatus(e.status))
        ? 4000
        : false,
  });

  const monitoredQuery = useQuery({
    queryKey: ["execution-events"],
    queryFn: () =>
      fetchExecutionEvents({
        limit: 100,
      }),
    refetchInterval: (q) =>
      (q.state.data?.events ?? []).some((event) =>
        isExternalLiveStatus(event.status),
      )
        ? 4000
        : false,
  });

  const executions = useMemo(
    () => query.data?.executions ?? [],
    [query.data?.executions],
  );

  const monitoredExecutions = useMemo(
    () =>
      groupExternalExecutions(
        monitoredQuery.data?.events ?? [],
      ),
    [monitoredQuery.data?.events],
  );

  const unifiedExecutions = useMemo<UnifiedExecution[]>(
    () => [
      ...executions.map((execution) => ({
        kind: "INTERNAL" as const,
        id: execution.id,
        workflowName: execution.workflowName ?? "Untitled workflow",
        status: execution.status,
        createdAt: execution.createdAt,
        durationMs: execution.durationMs,
        versionNumber: execution.versionNumber,
        triggerType: execution.triggerType,
        error: execution.error ?? null,
        searchText: [
          execution.workflowName,
          execution.id,
          execution.triggerType,
          execution.error,
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase(),
      })),

      ...monitoredExecutions.map((execution) => ({
        kind: "EXTERNAL" as const,
        key: execution.key,
        workflowName: execution.workflowName,
        status: execution.status,
        createdAt: execution.latestEvent.createdAt,
        durationMs: execution.durationMs,
        source: execution.source,
        executionExternalId: execution.executionExternalId,
        workflowExternalId: execution.workflowExternalId,
        nodeCount: execution.nodeCount,
        searchText: [
          execution.workflowName,
          execution.source,
          execution.executionExternalId,
          execution.workflowExternalId,
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase(),
      })),
    ],
    [executions, monitoredExecutions],
  );

  const visibleExecutions = useMemo(() => {
    const normalizedSearch = search.trim().toLowerCase();

    return unifiedExecutions
      .filter((execution) => {
        if (source !== "ALL" && execution.kind !== source) {
          return false;
        }

        if (
          status !== "ALL" &&
          execution.status.toUpperCase() !== status
        ) {
          return false;
        }

        if (
          normalizedSearch &&
          !execution.searchText.includes(normalizedSearch)
        ) {
          return false;
        }

        return true;
      })
      .sort(
        (a, b) =>
          Date.parse(b.createdAt) - Date.parse(a.createdAt),
      );
  }, [unifiedExecutions, source, status, search]);

  const stats = useMemo(() => {
    const all = unifiedExecutions;

    const active = all.filter((execution) =>
      ["RUNNING", "WAITING", "QUEUED"].includes(
        execution.status.toUpperCase(),
      ),
    ).length;

    const succeeded = all.filter(
      (execution) =>
        execution.status.toUpperCase() === "SUCCEEDED",
    ).length;

    const failed = all.filter(
      (execution) =>
        execution.status.toUpperCase() === "FAILED",
    ).length;

    const completed = all.filter((execution) =>
      ["SUCCEEDED", "FAILED", "CANCELED"].includes(
        execution.status.toUpperCase(),
      ),
    );

    return {
      total: all.length,
      active,
      succeeded,
      failed,
      successRate:
        completed.length > 0
          ? Math.round((succeeded / completed.length) * 1000) / 10
          : null,
    };
  }, [unifiedExecutions]);

  const hasFilters =
    source !== "ALL" ||
    status !== "ALL" ||
    search.trim().length > 0;

  const clearFilters = () => {
    setSource("ALL");
    setStatus("ALL");
    setSearch("");
  };

  const loading = query.isPending || monitoredQuery.isPending;
  const hasError = query.isError || monitoredQuery.isError;

  return (
    <PageContainer variant="wide">
      {/* PAGE HEADER */}
      <Reveal>
        <PageHeader
          eyebrow="Execution Control Plane"
          title="Executions"
          description="Monitor workflow runs, execution health and connected provider activity."
          actions={
            hasError ? (
              <div
                role="status"
                aria-live="polite"
                className="flex items-center gap-2 rounded-lg border border-red-400/20 bg-red-400/10 px-3 py-1.5"
                title="Could not connect to execution stream"
              >
                <span className="size-1.5 rounded-full bg-red-400" />
                <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-red-300">
                  Offline
                </span>
              </div>
            ) : stats.active > 0 ? (
              <div
                role="status"
                aria-live="polite"
                className="flex items-center gap-2 rounded-lg border border-emerald-400/20 bg-emerald-400/10 px-3 py-1.5"
                title="Active polling (every 4s) while executions are in flight"
              >
                <span className="relative flex size-2">
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                  <span className="relative inline-flex size-2 rounded-full bg-emerald-400" />
                </span>
                <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-emerald-300">
                  Live monitoring
                </span>
              </div>
            ) : (
              <div
                role="status"
                aria-live="polite"
                className="flex items-center gap-2 rounded-lg border border-border dark:border-white/[0.08] bg-muted/30 dark:bg-white/[0.025] px-3 py-1.5"
                title="Data up to date · Auto-polls when workflows run"
              >
                <span className="size-1.5 rounded-full bg-muted-foreground/40 dark:bg-white/35" />
                <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground dark:text-white/50">
                  Monitoring idle
                </span>
              </div>
            )
          }
        />
      </Reveal>

      {/* KPI STRIP */}
      <Reveal delay={0.04} distance={8}>
        <KpiStrip
          items={[
            {
              label: "Workflow runs",
              value: loading ? (
                <Skeleton className="h-7 w-16" />
              ) : (
                stats.total.toLocaleString()
              ),
              detail: loading ? "Loading" : stats.total === 0 ? "No runs yet" : "All executions",
              tone: "neutral",
            },
            {
              label: "Success rate",
              value: loading ? (
                <Skeleton className="h-7 w-20" />
              ) : stats.total === 0 || stats.successRate === null ? (
                "—"
              ) : (
                `${stats.successRate}%`
              ),
              detail: loading
                ? "Loading"
                : stats.total === 0
                  ? "No runs yet"
                  : stats.successRate === null
                    ? "No completed runs"
                    : getHealthLabel(stats.successRate),
              tone:
                loading || stats.total === 0 || stats.successRate === null
                  ? "neutral"
                  : getHealthTone(stats.successRate),
            },
            {
              label: "Failed runs",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                stats.failed.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : stats.total === 0
                  ? "No runs yet"
                  : stats.failed === 0
                    ? "No failures"
                    : "Requires attention",
              tone:
                loading || stats.total === 0
                  ? "neutral"
                  : stats.failed > 0
                    ? "danger"
                    : "success",
            },
            {
              label: "Active runs",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                stats.active.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : stats.active > 0
                  ? "Currently executing"
                  : "No active runs",
              tone: loading || stats.active === 0 ? "neutral" : "active",
            },
          ]}
        />
      </Reveal>

      {/* FILTERS */}
      <Reveal delay={0.08} distance={8}>
        <div className="mt-6">
          <Toolbar>
            {/* Search */}
            <div className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground/60 dark:text-white/40" />

              <Input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search executions..."
                className="pl-10 text-sm"
              />

              {search && (
                <button
                  type="button"
                  onClick={() => setSearch("")}
                  aria-label="Clear search"
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground/60 hover:text-foreground dark:text-white/40 dark:hover:text-white"
                >
                  <X className="size-3.5" />
                </button>
              )}
            </div>

            {/* Source Segmented Control */}
            <div className="flex h-10 shrink-0 items-center gap-1 rounded-lg border border-border dark:border-white/[0.08] bg-muted/40 dark:bg-[#0a0a0c] p-1">
              {(
                [
                  ["ALL", "All"],
                  ["INTERNAL", "Internal"],
                  ["EXTERNAL", "External"],
                ] as const
              ).map(([value, label]) => {
                const active = source === value;

                return (
                  <button
                    key={value}
                    type="button"
                    onClick={() => setSource(value)}
                    className={cn(
                      "h-8 rounded-md px-3 text-xs font-medium transition-colors",
                      active
                        ? "bg-background text-foreground font-semibold shadow-sm dark:bg-white dark:text-black"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground dark:text-white/50 dark:hover:bg-white/[0.04] dark:hover:text-white/80",
                    )}
                  >
                    {label}
                  </button>
                );
              })}
            </div>

            {/* Status Dropdown */}
            <div className="relative shrink-0">
              <select
                value={status}
                onChange={(event) =>
                  setStatus(event.target.value as ExecutionStatus | "ALL")
                }
                aria-label="Filter by status"
                className="h-10 appearance-none rounded-lg border border-border dark:border-white/[0.08] bg-background dark:bg-[#0a0a0c] pl-3 pr-9 text-xs font-medium text-foreground/80 dark:text-white/70 outline-none transition-colors hover:text-foreground dark:hover:text-white focus:border-foreground/30 dark:focus:border-white/20"
              >
                {STATUS_FILTERS.map((filter) => (
                  <option key={filter.value} value={filter.value}>
                    {filter.label}
                  </option>
                ))}
              </select>
              <ChevronDown className="pointer-events-none absolute right-3 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground/60 dark:text-white/40" />
            </div>

            {/* Clear */}
            {hasFilters && (
              <Button
                variant="outline"
                size="sm"
                onClick={clearFilters}
                className="h-10 text-xs"
              >
                Clear
              </Button>
            )}
          </Toolbar>
        </div>
      </Reveal>

      {/* EXECUTION LIST */}
      <div className="mt-6">
        <div className="mb-3 flex items-end justify-between px-0.5">
          <div>
            <h2 className="text-sm font-medium text-foreground/90 dark:text-white/80">
              Recent executions
            </h2>
            <p className="mt-0.5 text-xs text-muted-foreground dark:text-white/40">
              {hasFilters
                ? `${visibleExecutions.length} matching ${
                    visibleExecutions.length === 1 ? "run" : "runs"
                  }`
                : "Newest first"}
            </p>
          </div>

          {visibleExecutions.length > 0 && (
            <span className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground dark:text-white/40">
              {visibleExecutions.length} shown
            </span>
          )}
        </div>

        {loading ? (
          <TableSkeleton rows={8} />
        ) : hasError ? (
          <ErrorState
            title="Unable to load executions"
            message={
              query.isError
                ? getErrorMessage(query.error)
                : getErrorMessage(monitoredQuery.error)
            }
            onRetry={() => {
              void query.refetch();
              void monitoredQuery.refetch();
            }}
          />
        ) : visibleExecutions.length === 0 ? (
          <EmptyState
            icon={Activity}
            title={
              hasFilters ? "No matching executions" : "No executions yet"
            }
            description={
              hasFilters
                ? "Try a different source, status, or search term."
                : "Run a Vantis workflow or connect a provider to see executions here."
            }
            actions={
              hasFilters ? (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={clearFilters}
                >
                  Clear filters
                </Button>
              ) : undefined
            }
          />
        ) : (
          <>
            <div className="overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
              {/* TABLE HEADER */}
              <div className="sticky top-0 z-10 hidden h-10 items-center border-b border-border dark:border-white/[0.05] bg-card/95 dark:bg-[#0c0c0e]/95 px-4 font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground dark:text-white/40 backdrop-blur-sm md:flex">
                <div className="w-[120px]">Status</div>
                <div className="flex-1">Workflow</div>
                <div className="w-[110px] text-right">Duration</div>
                <div className="w-[105px] text-right">Started</div>
                <div className="w-6" />
              </div>

              <StaggerGroup stagger={0.02}>
                <ul className="divide-y divide-border dark:divide-white/[0.05]">
                  {visibleExecutions.slice(0, pageSize).map((execution) => (
                    <StaggerItem
                      key={
                        execution.kind === "INTERNAL"
                          ? `internal:${execution.id}`
                          : `external:${execution.key}`
                      }
                      distance={4}
                    >
                      <ExecutionRow execution={execution} />
                    </StaggerItem>
                  ))}
                </ul>
              </StaggerGroup>
            </div>

            <LoadMoreButton
              loadedCount={Math.min(pageSize, visibleExecutions.length)}
              totalCount={visibleExecutions.length}
              onLoadMore={() => setPageSize((prev) => prev + 25)}
            />
          </>
        )}
      </div>
    </PageContainer>
  );
}

function ExecutionRow({
  execution,
}: {
  execution: UnifiedExecution;
}) {
  if (execution.kind === "INTERNAL") {
    return (
      <li>
        <Link
          href={`/executions/${execution.id}`}
          className="group flex min-w-0 items-center gap-3 px-4 py-3.5 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.025] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground/20 dark:focus-visible:ring-white/20 md:gap-4"
        >
          <div className="w-[110px] shrink-0">
            <StatusBadge status={execution.status} />
          </div>

          <div className="min-w-0 flex-1">
            <div className="flex min-w-0 items-center gap-2">
              <span
                title={execution.workflowName}
                className="truncate text-sm font-medium text-foreground/90 group-hover:text-foreground dark:text-white/85 dark:group-hover:text-white"
              >
                {execution.workflowName}
              </span>

              <span className="hidden rounded border border-border dark:border-white/[0.08] bg-muted/50 dark:bg-white/[0.02] px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.1em] text-muted-foreground dark:text-white/50 lg:inline-flex">
                Internal
              </span>
            </div>

            <div
              title={execution.error ? `Error: ${execution.error}` : undefined}
              className="mt-0.5 truncate text-xs text-muted-foreground dark:text-white/50"
            >
              v{execution.versionNumber} ·{" "}
              {execution.triggerType.toLowerCase()} trigger
              {execution.error ? ` · ${execution.error}` : ""}
            </div>
          </div>

          <ExecutionMeta
            createdAt={execution.createdAt}
            durationMs={execution.durationMs}
          />

          <ArrowUpRight className="hidden size-4 shrink-0 text-muted-foreground/50 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-foreground dark:text-white/35 dark:group-hover:text-white/70 md:block" />
        </Link>
      </li>
    );
  }

  return (
    <li>
      <Link
        href={`/executions/external/${encodeURIComponent(
          execution.executionExternalId,
        )}?workflowExternalId=${encodeURIComponent(
          execution.workflowExternalId,
        )}`}
        className="group flex min-w-0 items-center gap-3 px-4 py-3.5 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.025] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground/20 dark:focus-visible:ring-white/20 md:gap-4"
      >
        <div className="w-[110px] shrink-0">
          <StatusBadge status={execution.status} />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <span
              title={execution.workflowName}
              className="truncate text-sm font-medium text-foreground/90 group-hover:text-foreground dark:text-white/85 dark:group-hover:text-white"
            >
              {execution.workflowName}
            </span>

            <span className="hidden rounded border border-border dark:border-white/[0.08] bg-muted/50 dark:bg-white/[0.02] px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.1em] text-muted-foreground dark:text-white/50 lg:inline-flex">
              External
            </span>

            <span className="hidden truncate font-mono text-[11px] uppercase tracking-[0.1em] text-muted-foreground dark:text-white/45 xl:inline">
              {execution.source}
            </span>
          </div>

          <div
            title={`run ${execution.executionExternalId}`}
            className="mt-0.5 truncate text-xs text-muted-foreground dark:text-white/50"
          >
            run {execution.executionExternalId} · {execution.nodeCount}{" "}
            {execution.nodeCount === 1 ? "node" : "nodes"}
          </div>
        </div>

        <ExecutionMeta
          createdAt={execution.createdAt}
          durationMs={execution.durationMs}
        />

        <ExternalLink className="hidden size-4 shrink-0 text-muted-foreground/50 transition-colors group-hover:text-foreground dark:text-white/35 dark:group-hover:text-white/70 md:block" />
      </Link>
    </li>
  );
}

function ExecutionMeta({
  createdAt,
  durationMs,
}: {
  createdAt: string;
  durationMs: number | null;
}) {
  return (
    <div className="hidden w-[215px] shrink-0 items-center justify-end gap-6 font-mono text-xs md:flex">
      <span className="w-[65px] text-right tabular-nums text-muted-foreground/90 dark:text-white/55">
        {formatDuration(durationMs)}
      </span>

      <span className="w-[85px] text-right text-muted-foreground dark:text-white/45">
        {formatRelativeTime(createdAt)}
      </span>
    </div>
  );
}

function groupExternalExecutions(
  events: ExecutionEvent[],
): MonitoredExecution[] {
  const groups = new Map<string, ExecutionEvent[]>();

  for (const event of events) {
    const key = [
      event.source,
      event.workflowExternalId,
      event.executionExternalId,
    ].join(":");

    const existing = groups.get(key);

    if (existing) {
      existing.push(event);
    } else {
      groups.set(key, [event]);
    }
  }

  return Array.from(groups.entries())
    .map(([key, group]) => {
      const sorted = [...group].sort(
        (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt),
      );

      const latestEvent = sorted[0];
      const status = deriveExternalStatus(group);

      const stepIds = new Set(
        group.map((event) => event.stepExternalId).filter(Boolean),
      );

      const workflowName = getExternalWorkflowName(latestEvent);

      const startedAt = earliestTimestamp(
        group.map((event) => event.startedAt),
      );

      const finishedAt = latestTimestamp(
        group.map((event) => event.finishedAt),
      );

      const durationMs =
        startedAt && finishedAt
          ? Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt))
          : null;

      return {
        key,
        source: latestEvent.source || "external",
        workflowExternalId: latestEvent.workflowExternalId,
        workflowName,
        executionExternalId: latestEvent.executionExternalId,
        status,
        events: sorted,
        nodeCount: stepIds.size || group.length,
        latestEvent,
        startedAt,
        finishedAt,
        durationMs,
      };
    })
    .sort(
      (a, b) =>
        Date.parse(b.latestEvent.createdAt) -
        Date.parse(a.latestEvent.createdAt),
    );
}

function deriveExternalStatus(events: ExecutionEvent[]): string {
  const statuses = events.map((event) => event.status.toUpperCase());

  if (statuses.includes("FAILED")) return "FAILED";
  if (statuses.includes("CANCELED")) return "CANCELED";
  if (statuses.includes("RUNNING")) return "RUNNING";
  if (statuses.includes("WAITING")) return "WAITING";
  if (statuses.includes("QUEUED")) return "QUEUED";
  if (statuses.includes("SUCCEEDED")) return "SUCCEEDED";
  if (statuses.includes("SKIPPED")) return "SKIPPED";

  return statuses[0] ?? "UNKNOWN";
}

function getExternalWorkflowName(event: ExecutionEvent): string {
  const metadata = event.metadata;

  if (metadata) {
    const name = metadata.workflowName;
    if (typeof name === "string" && name.trim()) {
      return name;
    }

    const workflow = metadata.workflow;
    if (typeof workflow === "string" && workflow.trim()) {
      return workflow;
    }
  }

  return event.workflowExternalId || "External workflow";
}

function earliestTimestamp(
  values: Array<string | null | undefined>,
): string | null {
  const valid = values.filter((value): value is string => Boolean(value));
  if (valid.length === 0) return null;

  return valid.reduce((earliest, value) =>
    Date.parse(value) < Date.parse(earliest) ? value : earliest,
  );
}

function latestTimestamp(
  values: Array<string | null | undefined>,
): string | null {
  const valid = values.filter((value): value is string => Boolean(value));
  if (valid.length === 0) return null;

  return valid.reduce((latest, value) =>
    Date.parse(value) > Date.parse(latest) ? value : latest,
  );
}

function isExternalLiveStatus(status: string): boolean {
  const normalized = status.toUpperCase();
  return (
    normalized === "RUNNING" ||
    normalized === "WAITING" ||
    normalized === "QUEUED"
  );
}