"use client";

import * as React from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  AlertTriangle,
  ArrowLeft,
  ChevronDown,
  Clock3,
  Copy,
  LayoutGrid,
  List,
  RefreshCw,
  X,
} from "lucide-react";

import { fetchExecutionEvents, getErrorMessage } from "@/lib/api";

import type { ExecutionEvent, ExecutionStatus } from "@/types";

import {
  executionStatusVariant,
  formatDateTime,
  formatDuration,
  formatRelativeTime,
} from "@/lib/format";

import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  ErrorState,
  PageContainer,
  PageHeader,
  StatusBadge,
  TableSkeleton,
} from "@/components/app/page-primitives";

import { ExecutionSummaryStrip } from "@/components/app/execution/ExecutionSummaryStrip";

/* ------------------------------------------------------------------ helpers */

const POLL_MS = 4000;

function normalize(status: string | null | undefined): string {
  return (status ?? "UNKNOWN").toUpperCase();
}

function isLive(status: string): boolean {
  const s = normalize(status);
  return s === "RUNNING" || s === "WAITING" || s === "QUEUED";
}

/** Same precedence the executions list uses, so both pages always agree. */
function deriveStatus(events: ExecutionEvent[]): string {
  const statuses = events.map((e) => normalize(e.status));

  for (const s of [
    "FAILED",
    "CANCELED",
    "RUNNING",
    "WAITING",
    "QUEUED",
    "SUCCEEDED",
    "SKIPPED",
  ]) {
    if (statuses.includes(s)) return s;
  }

  return statuses[0] ?? "UNKNOWN";
}

function minTime(values: Array<string | null | undefined>): string | null {
  const valid = values.filter((v): v is string => Boolean(v));
  if (valid.length === 0) return null;
  return valid.reduce((a, b) => (Date.parse(b) < Date.parse(a) ? b : a));
}

function maxTime(values: Array<string | null | undefined>): string | null {
  const valid = values.filter((v): v is string => Boolean(v));
  if (valid.length === 0) return null;
  return valid.reduce((a, b) => (Date.parse(b) > Date.parse(a) ? b : a));
}

function workflowNameOf(event: ExecutionEvent | undefined): string {
  if (!event) return "External workflow";

  const metadata = event.metadata as Record<string, unknown> | null | undefined;

  for (const key of ["workflowName", "workflow"]) {
    const value = metadata?.[key];
    if (typeof value === "string" && value.trim()) return value;
  }

  return event.workflowExternalId || "External workflow";
}

interface Step {
  id: string;
  name: string;
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  retryCount: number;
  errorType: string | null;
  errorMessage: string | null;
  /** Oldest → newest. */
  events: ExecutionEvent[];
}

function buildSteps(events: ExecutionEvent[]): Step[] {
  const groups = new Map<string, ExecutionEvent[]>();

  for (const event of events) {
    const id = event.stepExternalId || "workflow";
    const list = groups.get(id);
    if (list) list.push(event);
    else groups.set(id, [event]);
  }

  return Array.from(groups.entries())
    .map(([id, group]) => {
      const asc = [...group].sort(
        (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
      );

      // A step's state is whatever its most recent event says.
      const latest = asc[asc.length - 1];
      const startedAt = minTime(asc.map((e) => e.startedAt));
      const finishedAt = maxTime(asc.map((e) => e.finishedAt));

      const withError = [...asc].reverse().find((e) => e.errorMessage);

      return {
        id,
        name:
          [...asc].reverse().find((e) => e.stepName)?.stepName ||
          (id === "workflow" ? "Workflow" : id),
        status: normalize(latest.status),
        startedAt,
        finishedAt,
        durationMs:
          latest.durationMs ??
          (startedAt && finishedAt
            ? Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt))
            : null),
        retryCount: Math.max(...asc.map((e) => Math.max(e.retryCount ?? 0, 0))),
        errorType: normalize(latest.status) === "FAILED"
          ? (withError?.errorType ?? null)
          : null,
        errorMessage: normalize(latest.status) === "FAILED"
          ? (withError?.errorMessage ?? null)
          : null,
        events: asc,
      };
    })
    .sort(
      (a, b) =>
        Date.parse(a.startedAt ?? a.events[0].createdAt) -
        Date.parse(b.startedAt ?? b.events[0].createdAt),
    );
}

function dotColor(status: string) {
  switch (normalize(status)) {
    case "FAILED":
      return "bg-red-400/60";
    case "SUCCEEDED":
      return "bg-emerald-400/55";
    case "RUNNING":
      return "bg-blue-500 dark:bg-white/55";
    case "WAITING":
      return "bg-amber-400/60";
    case "QUEUED":
      return "bg-muted-foreground/50 dark:bg-white/30";
    default:
      return "bg-muted-foreground/30 dark:bg-white/20";
  }
}

/* ========================================================================= */

function ExternalExecutionDetail() {
  const params = useParams<{ executionExternalId: string }>();
  const searchParams = useSearchParams();

  const executionExternalId = React.useMemo(() => {
    const raw = String(params.executionExternalId ?? "");
    try {
      return decodeURIComponent(raw);
    } catch {
      return raw;
    }
  }, [params.executionExternalId]);

  const workflowExternalId = searchParams.get("workflowExternalId") ?? "";

  const [selectedStepId, setSelectedStepId] = React.useState<
    string | undefined
  >();

  const [view, setView] = React.useState<"graph" | "timeline">("graph");

  /*
   * The two ID filters need to exist in fetchExecutionEvents (and the backend)
   * to take effect. Until the backend applies them, the client-side filter
   * below keeps the page correct within the latest 500 events.
   */
  const eventsQuery = useQuery({
    queryKey: ["external-execution", executionExternalId, workflowExternalId],
    queryFn: () =>
      fetchExecutionEvents({
        executionExternalId,
        workflowExternalId: workflowExternalId || undefined,
        limit: 500,
      }),
    enabled: Boolean(executionExternalId),
    placeholderData: keepPreviousData,
    refetchInterval: (q) =>
      (q.state.data?.events ?? []).some(
        (e) =>
          e.executionExternalId === executionExternalId && isLive(e.status),
      )
        ? POLL_MS
        : false,
  });

  const events = React.useMemo(
    () =>
      (eventsQuery.data?.events ?? [])
        .filter(
          (e) =>
            e.executionExternalId === executionExternalId &&
            (!workflowExternalId ||
              e.workflowExternalId === workflowExternalId),
        )
        .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt)),
    [eventsQuery.data?.events, executionExternalId, workflowExternalId],
  );

  const steps = React.useMemo(() => buildSteps(events), [events]);

  const status = React.useMemo(() => deriveStatus(events), [events]);
  const live = isLive(status);

  const startedAt = React.useMemo(
    () => minTime(events.map((e) => e.startedAt)),
    [events],
  );

  const finishedAt = React.useMemo(
    () => (live ? null : maxTime(events.map((e) => e.finishedAt))),
    [events, live],
  );

  const durationMs =
    startedAt && finishedAt
      ? Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt))
      : null;

  const summary = React.useMemo(() => {
    let executed = 0;
    let failed = 0;
    let skipped = 0;
    let running = 0;

    for (const step of steps) {
      switch (step.status) {
        case "SUCCEEDED":
          executed++;
          break;
        case "FAILED":
          executed++;
          failed++;
          break;
        case "SKIPPED":
          skipped++;
          break;
        case "RUNNING":
          running++;
          break;
      }
    }

    return { total: steps.length, executed, failed, skipped, running };
  }, [steps]);

  const retryCount = steps.reduce((sum, s) => sum + s.retryCount, 0);

  const failedStep = steps.find((s) => s.status === "FAILED" && s.errorMessage);

  const selectedStep = steps.find((s) => s.id === selectedStepId) ?? null;

  const first = events[0];
  const workflowName = workflowNameOf(first);

  /* ------------------------------------------------------------ guards */

  if (eventsQuery.isPending) {
    return (
      <PageContainer variant="narrow">
        <TableSkeleton rows={6} />
      </PageContainer>
    );
  }

  if (eventsQuery.isError || events.length === 0) {
    return (
      <PageContainer variant="narrow">
        <ErrorState
          title={eventsQuery.isError ? "Could not load execution" : "No captured events"}
          message={
            eventsQuery.isError
              ? getErrorMessage(eventsQuery.error)
              : `No captured events found for run ${executionExternalId}.`
          }
          onRetry={() => eventsQuery.refetch()}
        />
      </PageContainer>
    );
  }

  /* -------------------------------------------------------------- page */

  return (
    <PageContainer variant="narrow">
      <PageHeader
        backLink={{ href: "/executions", label: "Executions" }}
        eyebrow="Execution Control Plane"
        title={workflowName}
        description={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground dark:text-white/50">
            <StatusBadge status={status} />
            <span>{first.source || "external"}</span>
            <span className="font-mono text-xs">run {executionExternalId}</span>
            <span title={startedAt ? formatDateTime(startedAt) : undefined}>
              started {formatRelativeTime(startedAt ?? first.createdAt)}
            </span>
            <span>·</span>
            <span>{formatDuration(durationMs)}</span>
            {live && (
              <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                <span className="size-1.5 animate-pulse rounded-full bg-emerald-500 dark:bg-emerald-400" />
                Live
              </span>
            )}
          </span>
        }
        actions={
          <Button
            variant="outline"
            onClick={() => eventsQuery.refetch()}
            disabled={eventsQuery.isFetching}
          >
            <RefreshCw
              className={cn("size-4", eventsQuery.isFetching && "animate-spin")}
            />
            <span>Refresh</span>
          </Button>
        }
      />

      {/* failure banner */}

      {status === "FAILED" && failedStep && (
        <div className="flex items-start gap-2 rounded-md border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />

          <span className="min-w-0 break-words">
            <span className="font-medium">{failedStep.name}</span>
            {failedStep.errorType ? ` (${failedStep.errorType})` : ""}:{" "}
            {failedStep.errorMessage}
          </span>
        </div>
      )}

      {/* retry banner */}

      {retryCount > 0 && (
        <div className="flex items-center gap-3 rounded-md border border-amber-500/20 bg-amber-500/[0.04] px-4 py-2.5 text-sm text-amber-700 dark:text-amber-200/80">
          <Clock3 className="size-4 shrink-0 text-amber-600 dark:text-amber-300/70" />
          {retryCount} {retryCount === 1 ? "retry was" : "retries were"}{" "}
          recorded during this run.
        </div>
      )}

      {/* summary strip */}

      <ExecutionSummaryStrip
        durationMs={durationMs}
        totalNodes={summary.total}
        executed={summary.executed}
        failed={summary.failed}
        skipped={summary.skipped}
        running={summary.running}
        status={status as ExecutionStatus}
      />

      {/* view tabs */}

      <div className="flex items-center gap-1 border-b border-border dark:border-white/[0.08]">
        {(
          [
            ["graph", "Graph", LayoutGrid],
            ["timeline", "Timeline", List],
          ] as const
        ).map(([value, label, Icon]) => (
          <button
            key={value}
            type="button"
            onClick={() => setView(value)}
            className={cn(
              "-mb-px flex items-center gap-2 border-b-2 px-4 py-2.5 text-sm font-medium transition",
              view === value
                ? "border-primary text-foreground dark:border-white/60 dark:text-white/90"
                : "border-transparent text-muted-foreground hover:text-foreground dark:text-white/44 dark:hover:text-white/70",
            )}
          >
            <Icon className="size-4" />
            {label}
          </button>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* main panel */}

        <div className="space-y-3 lg:col-span-2">
          {view === "graph" ? (
            <ExternalGraph
              steps={steps}
              selectedStepId={selectedStepId}
              onSelect={(id) =>
                setSelectedStepId((cur) => (cur === id ? undefined : id))
              }
            />
          ) : (
            <div className="space-y-1.5">
              {steps.map((step) => (
                <StepRow
                  key={step.id}
                  step={step}
                  selected={step.id === selectedStepId}
                  onClick={() =>
                    setSelectedStepId((cur) =>
                      cur === step.id ? undefined : step.id,
                    )
                  }
                />
              ))}
            </div>
          )}

          <details className="group rounded-xl border border-border dark:border-white/[0.08] [&_summary::-webkit-details-marker]:hidden">
            <summary className="flex cursor-pointer list-none items-center gap-2 px-4 py-2.5 text-sm text-foreground">
              <ChevronDown className="size-4 text-muted-foreground dark:text-white/44 transition-transform group-open:rotate-180" />
              <span className="font-medium">Execution details</span>
            </summary>

            <div className="grid border-t border-border dark:border-white/[0.06] sm:grid-cols-2">
              <DetailItem label="Execution ID" value={executionExternalId} copyable />
              <DetailItem
                label="Workflow ID"
                value={first.workflowExternalId || workflowExternalId || "—"}
                copyable
              />
              <DetailItem label="Source" value={first.source || "—"} />
              <DetailItem label="Events captured" value={String(events.length)} />
              <DetailItem
                label="Started"
                value={startedAt ? formatDateTime(startedAt) : "—"}
              />
              <DetailItem
                label="Finished"
                value={finishedAt ? formatDateTime(finishedAt) : "—"}
              />
            </div>
          </details>
        </div>

        {/* inspector + activity */}

        <div className="space-y-6 lg:col-span-1">
          {selectedStep && (
            <StepInspector
              step={selectedStep}
              onClose={() => setSelectedStepId(undefined)}
            />
          )}

          <ActivityPanel events={events} />
        </div>
      </div>
    </PageContainer>
  );
}

/* useSearchParams requires a Suspense boundary for production builds. */
export default function ExternalExecutionPage() {
  return (
    <React.Suspense fallback={null}>
      <ExternalExecutionDetail />
    </React.Suspense>
  );
}

/* ----------------------------------------------------------------- pieces */

function StepRow({
  step,
  selected,
  onClick,
}: {
  step: Step;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-3 rounded-xl border bg-card dark:bg-[#0b0b0c] px-4 py-3 text-left transition hover:bg-muted/40 dark:hover:bg-white/[0.03]",
        selected ? "border-foreground/30 dark:border-white/[0.20]" : "border-border dark:border-white/[0.08]",
      )}
    >
      <span
        className={cn(
          "size-1.5 shrink-0 rounded-full",
          dotColor(step.status),
          isLive(step.status) && "animate-pulse",
        )}
      />

      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="truncate text-sm font-medium text-foreground/90 dark:text-white/80">
            {step.name}
          </span>

          {step.retryCount > 0 && (
            <span className="shrink-0 rounded border border-amber-500/20 bg-amber-500/[0.05] px-1.5 py-0.5 font-mono text-[9px] text-amber-700 dark:text-amber-300/70">
              retry ×{step.retryCount}
            </span>
          )}
        </span>

        {step.errorMessage && (
          <span className="mt-0.5 block truncate text-xs text-destructive">
            {step.errorMessage}
          </span>
        )}
      </span>

      <span className="text-xs capitalize text-muted-foreground dark:text-white/44">
        {step.status.toLowerCase()}
      </span>

      <span className="w-16 text-right font-mono text-xs text-muted-foreground dark:text-white/45">
        {formatDuration(step.durationMs)}
      </span>
    </button>
  );
}

function StepInspector({
  step,
  onClose,
}: {
  step: Step;
  onClose: () => void;
}) {
  const metadata = [...step.events]
    .reverse()
    .find((e) => e.metadata && Object.keys(e.metadata).length > 0)?.metadata;

  return (
    <Card className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0a]">
      <CardHeader className="flex flex-row items-start justify-between gap-2 pb-3">
        <CardTitle className="min-w-0 truncate text-base text-foreground dark:text-white/90">
          {step.name}
        </CardTitle>

        <button
          type="button"
          onClick={onClose}
          aria-label="Close inspector"
          className="text-muted-foreground hover:text-foreground dark:text-white/45 dark:hover:text-white/80 transition"
        >
          <X className="size-4" />
        </button>
      </CardHeader>

      <CardContent className="space-y-4 text-sm">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-xs">
          <dt className="text-muted-foreground dark:text-white/45">Step ID</dt>
          <dd className="break-all font-mono text-foreground/80 dark:text-white/70">{step.id}</dd>

          <dt className="text-muted-foreground dark:text-white/45">Status</dt>
          <dd className="capitalize text-foreground/80 dark:text-white/70">
            {step.status.toLowerCase()}
          </dd>

          <dt className="text-muted-foreground dark:text-white/45">Started</dt>
          <dd className="text-foreground/80 dark:text-white/70">
            {step.startedAt ? formatDateTime(step.startedAt) : "—"}
          </dd>

          <dt className="text-muted-foreground dark:text-white/45">Finished</dt>
          <dd className="text-foreground/80 dark:text-white/70">
            {step.finishedAt ? formatDateTime(step.finishedAt) : "—"}
          </dd>

          <dt className="text-muted-foreground dark:text-white/45">Duration</dt>
          <dd className="text-foreground/80 dark:text-white/70">{formatDuration(step.durationMs)}</dd>

          <dt className="text-muted-foreground dark:text-white/45">Retries</dt>
          <dd className="text-foreground/80 dark:text-white/70">{step.retryCount}</dd>
        </dl>

        {step.errorMessage && (
          <div className="rounded-md border border-destructive/20 bg-destructive/5 p-3 text-xs">
            <p className="font-medium text-destructive">
              {step.errorType || "Execution error"}
            </p>
            <p className="mt-1 break-words text-muted-foreground dark:text-white/60">
              {step.errorMessage}
            </p>
          </div>
        )}

        <div className="space-y-1.5">
          <p className="text-xs font-medium text-muted-foreground dark:text-white/50">Metadata</p>
          <JsonBlock value={metadata} />
        </div>
      </CardContent>
    </Card>
  );
}

function ActivityPanel({ events }: { events: ExecutionEvent[] }) {
  const scrollRef = React.useRef<HTMLOListElement>(null);

  React.useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [events.length]);

  return (
    <Card className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0a] lg:sticky lg:top-6">
      <CardHeader className="pb-3">
        <CardTitle className="text-base text-foreground dark:text-white/90">Activity</CardTitle>
      </CardHeader>

      <CardContent>
        <ol
          ref={scrollRef}
          className="max-h-[60vh] space-y-1.5 overflow-auto font-mono text-xs"
        >
          {events.map((event) => {
            const s = normalize(event.status);

            return (
              <li key={event.id} className="flex gap-2">
                <span
                  className={cn(
                    "w-20 shrink-0 font-medium",
                    s === "FAILED" ? "text-destructive" : "text-muted-foreground dark:text-white/44",
                  )}
                >
                  {s}
                </span>

                <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-foreground/80 dark:text-white/60">
                  {event.stepName || event.stepExternalId || "workflow"}
                  {event.errorMessage ? ` — ${event.errorMessage}` : ""}
                </span>
              </li>
            );
          })}
        </ol>
      </CardContent>
    </Card>
  );
}

function DetailItem({
  label,
  value,
  copyable = false,
}: {
  label: string;
  value: string;
  copyable?: boolean;
}) {
  async function copyValue() {
    if (!value || value === "—") return;

    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Clipboard access may be unavailable.
    }
  }

  return (
    <div className="group flex min-w-0 items-center justify-between gap-4 border-b border-border dark:border-white/[0.06] px-4 py-3">
      <div className="min-w-0">
        <p className="text-xs text-muted-foreground dark:text-white/45">{label}</p>
        <p className="mt-0.5 break-all font-mono text-xs text-foreground/80 dark:text-white/60">
          {value}
        </p>
      </div>

      {copyable && value !== "—" && (
        <button
          type="button"
          onClick={copyValue}
          aria-label={`Copy ${label}`}
          className="shrink-0 rounded-md p-1.5 text-muted-foreground/60 opacity-0 transition hover:bg-muted hover:text-foreground dark:text-white/35 dark:hover:bg-white/[0.05] dark:hover:text-white group-hover:opacity-100"
        >
          <Copy className="size-3.5" />
        </button>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------- graph */

const NODE_W = 190;
const NODE_H = 56;
const GAP_X = 72;
const GAP_Y = 18;
const PAD = 16;

function startTime(step: Step): number {
  return Date.parse(step.startedAt ?? step.events[0].createdAt);
}

/**
 * Events don't record which step feeds which, so layers are inferred from
 * timing: steps whose time ranges overlap run in parallel (same layer);
 * otherwise a step belongs to the next layer.
 */
function layoutSteps(steps: Step[]): Step[][] {
  const sorted = [...steps].sort((a, b) => startTime(a) - startTime(b));
  const layers: Step[][] = [];
  let layerEnd = -Infinity;

  for (const step of sorted) {
    const start = startTime(step);
    const end = step.finishedAt
      ? Date.parse(step.finishedAt)
      : isLive(step.status)
        ? Infinity
        : start;

    if (layers.length > 0 && start < layerEnd) {
      layers[layers.length - 1].push(step);
      layerEnd = Math.max(layerEnd, end);
    } else {
      layers.push([step]);
      layerEnd = end;
    }
  }

  return layers;
}

function dotFill(status: string) {
  switch (normalize(status)) {
    case "FAILED":
      return "fill-red-400/70";
    case "SUCCEEDED":
      return "fill-emerald-400/70";
    case "RUNNING":
      return "fill-white/70";
    case "WAITING":
      return "fill-amber-400/70";
    default:
      return "fill-white/30";
  }
}

function clip(text: string, max: number) {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function ExternalGraph({
  steps,
  selectedStepId,
  onSelect,
}: {
  steps: Step[];
  selectedStepId: string | undefined;
  onSelect: (id: string) => void;
}) {
  const layers = React.useMemo(() => layoutSteps(steps), [steps]);

  const maxRows = Math.max(1, ...layers.map((l) => l.length));
  const innerH = maxRows * NODE_H + (maxRows - 1) * GAP_Y;
  const width = layers.length * NODE_W + (layers.length - 1) * GAP_X + PAD * 2;
  const height = innerH + PAD * 2;

  const positions = new Map<string, { x: number; y: number }>();

  layers.forEach((layer, col) => {
    const colH = layer.length * NODE_H + (layer.length - 1) * GAP_Y;
    const top = PAD + (innerH - colH) / 2;

    layer.forEach((step, row) => {
      positions.set(step.id, {
        x: PAD + col * (NODE_W + GAP_X),
        y: top + row * (NODE_H + GAP_Y),
      });
    });
  });

  const edges: Array<{ key: string; d: string }> = [];

  for (let i = 0; i < layers.length - 1; i++) {
    for (const from of layers[i]) {
      for (const to of layers[i + 1]) {
        const a = positions.get(from.id)!;
        const b = positions.get(to.id)!;
        const x1 = a.x + NODE_W;
        const y1 = a.y + NODE_H / 2;
        const x2 = b.x;
        const y2 = b.y + NODE_H / 2;
        const mid = (x1 + x2) / 2;

        edges.push({
          key: `${from.id}->${to.id}`,
          d: `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`,
        });
      }
    }
  }

  return (
    <div className="space-y-2">
      <div className="overflow-auto rounded-xl border border-border dark:border-white/[0.08] bg-card dark:bg-[#0b0b0c]">
        <svg
          width={width}
          height={height}
          role="group"
          aria-label="Execution graph"
          className="block"
        >
          {edges.map((edge) => (
            <path
              key={edge.key}
              d={edge.d}
              className="fill-none stroke-border dark:stroke-white/15"
              strokeWidth={1.5}
            />
          ))}

          {steps.map((step) => {
            const p = positions.get(step.id);
            if (!p) return null;

            const selected = step.id === selectedStepId;

            return (
              <g
                key={step.id}
                transform={`translate(${p.x} ${p.y})`}
                role="button"
                tabIndex={0}
                aria-label={`${step.name}, ${step.status.toLowerCase()}`}
                onClick={() => onSelect(step.id)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelect(step.id);
                  }
                }}
                className="cursor-pointer outline-none"
              >
                <title>{step.name}</title>

                <rect
                  width={NODE_W}
                  height={NODE_H}
                  rx={10}
                  className={cn(
                    "fill-muted/60 dark:fill-[#111113] transition-colors",
                    selected
                      ? "stroke-foreground/40 dark:stroke-white/50"
                      : step.status === "FAILED"
                        ? "stroke-red-500/30 dark:stroke-red-400/30"
                        : "stroke-border dark:stroke-white/10",
                  )}
                  strokeWidth={1.5}
                />

                <circle
                  cx={16}
                  cy={NODE_H / 2}
                  r={4}
                  className={cn(
                    dotFill(step.status),
                    isLive(step.status) && "animate-pulse",
                  )}
                />

                <text
                  x={30}
                  y={24}
                  className="fill-foreground/90 dark:fill-white/80 text-[12px] font-medium"
                >
                  {clip(step.name, 22)}
                </text>

                <text x={30} y={42} className="fill-muted-foreground dark:fill-white/35 font-mono text-[10px]">
                  {step.status.toLowerCase()} · {formatDuration(step.durationMs)}
                  {step.retryCount > 0 ? ` · ×${step.retryCount}` : ""}
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      <p className="text-xs text-muted-foreground dark:text-white/45">
        Layout is inferred from run order: steps that overlap in time are shown
        in parallel. Connections from the provider aren&apos;t captured.
      </p>
    </div>
  );
}

function JsonBlock({ value }: { value: unknown }) {
  if (value === null || value === undefined) {
    return <p className="text-xs text-muted-foreground dark:text-white/44">None recorded.</p>;
  }

  return (
    <pre className="max-h-64 overflow-auto rounded-md border border-border dark:border-white/[0.08] bg-muted/40 dark:bg-[#0a0a0a] text-foreground/90 dark:text-white/80 p-3 font-mono text-xs leading-relaxed">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}