"use client";

import {
  useMemo,
  useState,
  type ReactNode,
} from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  Plus,
} from "lucide-react";
import {
  PageContainer,
  PageHeader,
  KpiStrip,
  StatusBadge,
} from "@/components/app/page-primitives";

import {
  fetchAnomalies,
  fetchCurrentOrganization,
  fetchExecutionStats,
  fetchExecutions,
} from "@/lib/api";

import {
  formatDuration,
  formatRelativeTime,
  isLiveStatus,
} from "@/lib/format";
import {
  getHealthLabel,
  getHealthTone,
  getHealthStroke,
} from "@/lib/status";

import type {
  ExecutionStatus,
  ExecutionSummary,
  StatsRange,
} from "@/types";

import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import {
  Reveal,
  StaggerGroup,
  StaggerItem,
  HoverLift,
} from "@/components/motion/motion-primitives";


/* ==========================================================================
   CONSTANTS
============================================================================ */

const RANGE_OPTIONS: StatsRange[] = [
  "24h",
  "7d",
  "30d",
  "90d",
];

/* ==========================================================================
   PAGE
============================================================================ */

export default function DashboardPage() {
  const [range, setRange] =
    useState<StatsRange>("7d");

  /* ------------------------------------------------------------------------
     EXECUTIONS
  ------------------------------------------------------------------------ */

  const executionsQuery = useQuery({
    queryKey: [
      "executions",
      "dashboard",
    ],

    queryFn: () =>
      fetchExecutions({
        limit: 12,
      }),

    staleTime: 10_000,

    refetchInterval: query => {
      const executions =
        query.state.data
          ?.executions ?? [];

      const hasLiveExecution =
        executions.some(execution =>
          isLiveStatus(
            execution.status,
          ),
        );

      return hasLiveExecution
        ? 5_000
        : false;
    },
  });

  /* ------------------------------------------------------------------------
     STATS
  ------------------------------------------------------------------------ */

  const statsQuery = useQuery({
    queryKey: [
      "executions",
      "stats",
      range,
    ],

    queryFn: () =>
      fetchExecutionStats(range),

    staleTime: 15_000,

    refetchInterval: 15_000,
  });

  /* ------------------------------------------------------------------------
     ORGANIZATION
  ------------------------------------------------------------------------ */

  const organizationQuery =
    useQuery({
      queryKey: [
        "org",
        "current",
      ],

      queryFn:
        fetchCurrentOrganization,

      staleTime:
        5 * 60 * 1000,
    });

  /* ------------------------------------------------------------------------
     OPEN ANOMALIES
     ------------------------------------------------------------------------

     ExecutionStats intentionally does not contain anomaly counts.
     Anomalies are their own reliability resource.
  ------------------------------------------------------------------------ */

  const anomaliesQuery =
    useQuery({
      queryKey: [
        "reliability",
        "anomalies",
        "open",
      ],

      queryFn: () =>
        fetchAnomalies({
          status: "OPEN",
        }),

      staleTime: 15_000,

      refetchInterval: 15_000,
    });

  /* ------------------------------------------------------------------------
     DATA
  ------------------------------------------------------------------------ */

  const executions = useMemo(
    () => executionsQuery.data?.executions ?? [],
    [executionsQuery.data?.executions],
  );

  const stats =
    statsQuery.data;

  const totals =
    stats?.totals;

  const series = useMemo(
    () => statsQuery.data?.series ?? [],
    [statsQuery.data?.series],
  );

  const openAnomalies =
    anomaliesQuery.data
      ?.anomalies ?? [];

  /* ------------------------------------------------------------------------
     KPI VALUES
  ------------------------------------------------------------------------ */

  const totalRuns =
    totals?.total ?? 0;

  /*
   * Backend returns successRate as a 0–1 fraction.
   *
   * Example:
   * 0.9872 -> 98.72%
   */
  const successRate =
    (totals?.successRate ?? 0) *
    100;

  const failedRuns =
    totals?.failed ?? 0;

  /*
   * Active means all non-terminal execution
   * states represented by the stats contract.
   */
  const activeRuns =
    (totals?.running ?? 0) +
    (totals?.waiting ?? 0) +
    (totals?.queued ?? 0);

  const avgDurationMs =
    totals?.avgDurationMs ??
    null;

  const activeAnomalies =
    openAnomalies.length;

  const highPriorityAnomalies =
    openAnomalies.filter(
      anomaly =>
        anomaly.severity ===
          "HIGH" ||
        anomaly.severity ===
          "CRITICAL",
    ).length;

  /* ------------------------------------------------------------------------
     RECENT EXECUTIONS
  ------------------------------------------------------------------------ */

  const recentExecutions =
    useMemo<ExecutionSummary[]>(
      () => executions,
      [executions],
    );

  /* ------------------------------------------------------------------------
     RANGE LABEL
  ------------------------------------------------------------------------ */

  const rangeLabel =
    getRangeLabel(range);

  /* ------------------------------------------------------------------------
     ORGANIZATION NAME
  ------------------------------------------------------------------------ */

  const organizationName =
    organizationQuery.data
      ?.organization.name ??
    "Your organization";

  /* ------------------------------------------------------------------------
     CHART
  ------------------------------------------------------------------------ */

  const chart =
    useMemo(
      () =>
        buildChart(
          series,
        ),
      [series],
    );

  /* ------------------------------------------------------------------------
     LOADING
  ------------------------------------------------------------------------ */

  const initialLoading =
    executionsQuery.isPending ||
    statsQuery.isPending;

  /* ------------------------------------------------------------------------
     RENDER
  ------------------------------------------------------------------------ */

  const hasAnyRuns =
    series.some(point => (point.total ?? 0) > 0 || (point.failed ?? 0) > 0);

  return (
    <PageContainer variant="wide">
      <PageHeader
        eyebrow="Execution Control Plane"
        title="Dashboard"
        description={`Monitor workflow health and reliability across ${organizationName}.`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex h-10 items-center gap-1 rounded-lg border border-white/[0.08] bg-[#0a0a0c] p-1">
              {RANGE_OPTIONS.map((value) => {
                const active = range === value;

                return (
                  <button
                    key={value}
                    type="button"
                    onClick={() => setRange(value)}
                    className={cn(
                      "h-8 rounded-md px-3 text-xs font-medium transition-colors",
                      active
                        ? "bg-white text-black font-semibold shadow-sm"
                        : "text-white/45 hover:text-white/80 hover:bg-white/[0.04]",
                    )}
                  >
                    {getRangeLabel(value)}
                  </button>
                );
              })}
            </div>

            <Link href="/workflows">
              <Button className="gap-2">
                <Plus className="size-4" />
                <span>New workflow</span>
              </Button>
            </Link>
          </div>
        }
      />

      <KpiStrip
        items={[
          {
            label: "Workflow runs",
            value: initialLoading ? (
              <Skeleton className="h-7 w-20" />
            ) : (
              totalRuns.toLocaleString()
            ),
            detail: initialLoading
              ? "Loading"
              : totalRuns === 0
                ? "No runs yet"
                : rangeLabel,
            tone: "neutral",
          },
          {
            label: "Success rate",
            value: initialLoading ? (
              <Skeleton className="h-7 w-20" />
            ) : totalRuns === 0 ? (
              "—"
            ) : (
              `${successRate.toFixed(2)}%`
            ),
            detail:
              initialLoading
                ? "Loading"
                : totalRuns === 0
                  ? "No runs yet"
                  : getHealthLabel(successRate),
            tone:
              initialLoading || totalRuns === 0
                ? "neutral"
                : getHealthTone(successRate),
          },
          {
            label: "Failed runs",
            value: initialLoading ? (
              <Skeleton className="h-7 w-14" />
            ) : (
              failedRuns.toLocaleString()
            ),
            detail:
              initialLoading
                ? "Loading"
                : totalRuns === 0
                  ? "No runs yet"
                  : failedRuns > 0
                    ? "Needs attention"
                    : "No failures",
            tone:
              initialLoading || totalRuns === 0
                ? "neutral"
                : failedRuns > 0
                  ? "danger"
                  : "success",
          },
          {
            label: "Active anomalies",
            value: anomaliesQuery.isPending ? (
              <Skeleton className="h-7 w-14" />
            ) : (
              activeAnomalies.toLocaleString()
            ),
            detail:
              anomaliesQuery.isPending
                ? "Loading"
                : activeAnomalies === 0
                  ? "All systems normal"
                  : highPriorityAnomalies > 0
                    ? `${highPriorityAnomalies} critical / high priority`
                    : "Under investigation",
            tone:
              activeAnomalies > 0
                ? highPriorityAnomalies > 0
                  ? "danger"
                  : "warning"
                : "success",
          },
          {
            label: "Mean execution",
            value: initialLoading ? (
              <Skeleton className="h-7 w-20" />
            ) : avgDurationMs !== null ? (
              formatDuration(avgDurationMs)
            ) : (
              "—"
            ),
            detail:
              avgDurationMs !== null
                ? "Average duration"
                : "No completed runs",
            tone: "neutral",
          },
        ]}
      />

        {/* ================================================================
            HEALTH / RELIABILITY
        ================================================================ */}

        <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">

          {/* ==============================================================
              WORKFLOW HEALTH
          ============================================================== */}

          <Reveal delay={0.06} distance={10}>
  <Card className="flex h-full flex-col overflow-hidden rounded-xl border-white/[0.065] bg-[#0a0a0c] shadow-none">
    <CardContent className="flex flex-1 flex-col p-0">

      <div className="flex items-center justify-between border-b border-white/[0.055] px-5 py-4">
        <div>
          <h2 className="text-sm font-medium text-white/80">
            Workflow health
          </h2>
          <p className="mt-1 text-[11px] text-white/45">
            Executions and failures over time
          </p>
        </div>

        <div className="flex items-center gap-4">
          <ChartLegend label="Runs" color="bg-indigo-400" />
          <ChartLegend label="Failed" color="bg-red-400" />
        </div>
      </div>

      {/* was: relative h-[300px] */}
      <div className="relative min-h-[300px] flex-1 px-4 pb-5 pt-5 sm:px-5">
        {statsQuery.isPending ? (
          <ChartSkeleton />
        ) : series.length === 0 || !hasAnyRuns || totalRuns === 0 ? (
          <EmptyChart />
        ) : (
          <ExecutionChart chart={chart} />
        )}
      </div>
    </CardContent>
  </Card>
</Reveal>
          {/* ==============================================================
              RELIABILITY
          ============================================================== */}

          <Reveal
            delay={0.09}
            distance={10}
          >
            <ReliabilityCard
              loading={
                statsQuery.isPending
              }
              successRate={
                successRate
              }
              failedRuns={
                failedRuns
              }
              activeRuns={
                activeRuns
              }
              anomalies={
                activeAnomalies
              }
              totalRuns={
                totalRuns
              }
            />
          </Reveal>
        </div>

        {/* ================================================================
            RECENT EXECUTIONS
        ================================================================ */}

        <Reveal
          delay={0.12}
          distance={10}
        >
          <section className="mt-5 overflow-hidden rounded-xl border border-white/[0.065] bg-[#0D0D10]">

            <div className="flex items-center justify-between border-b border-white/[0.055] px-5 py-4">
              <div>
                <h2 className="text-sm font-medium text-white/80">
                  Recent executions
                </h2>

                <p className="mt-1 text-[11px] text-white/45">
                  Latest workflow activity
                  across your workspace
                </p>
              </div>

              <Link
                href="/executions"
                className="flex items-center gap-1.5 text-[11px] text-white/45 transition-colors hover:text-white/85"
              >
                View all
                <ArrowRight className="size-3" />
              </Link>
            </div>

            {executionsQuery.isPending ? (
              <ExecutionTableSkeleton />
            ) : executionsQuery.isError ? (
              <QueryError
                message="Unable to load recent executions."
                onRetry={() =>
                  executionsQuery.refetch()
                }
              />
            ) : recentExecutions.length ===
              0 ? (
              <EmptyExecutions />
            ) : (
              <ExecutionTable
                executions={
                  recentExecutions
                }
              />
            )}
          </section>
        </Reveal>

        {/* ================================================================
            QUICK LINKS
        ================================================================ */}

        <StaggerGroup stagger={0.035}>
          <div className="mt-5 grid gap-3 sm:grid-cols-3">

            <StaggerItem distance={8}>
              <DashboardLink
                href="/executions"
                title="Execution history"
                description="Inspect runs, timing and outputs"
              />
            </StaggerItem>

            <StaggerItem distance={8}>
              <DashboardLink
                href="/anomalies"
                title="Reliability"
                description="Review anomalies and behavioral drift"
              />
            </StaggerItem>

            <StaggerItem distance={8}>
              <DashboardLink
                href="/integrations"
                title="Integrations"
                description="Manage workflow connections"
              />
            </StaggerItem>
          </div>
        </StaggerGroup>

        {/* ================================================================
            FOOTER
        ================================================================ */}

        <div className="mt-6 flex items-center justify-between border-t border-white/[0.045] pt-3">
          <span className="text-caption text-white/40">
            {rangeLabel}
          </span>

          <span className="font-mono text-caption uppercase tracking-[0.13em] text-white/45">
            Vantis / EXECUTION CONTROL
          </span>
        </div>
    </PageContainer>
  );
}

/* ==========================================================================
   CHART
============================================================================ */

type ChartPoint = {
  x: number;
  total: number;
  failed: number;
  label: string;
};

function buildChart(
  series: Array<{
    bucketStart: string;
    total: number;
    failed: number;
  }>,
) {
  const width = 760;
  const height = 240;
  const paddingX = 12;
  const paddingTop = 12;
  const paddingBottom = 12;

  if (!series.length) {
  return {
    width,
    height,
    points: [] as ChartPoint[],
    max: 1,
    paddingTop,
    usableHeight:
      height -
      paddingTop -
      paddingBottom,
  };
}

  const max = Math.max(
    ...series.map(point =>
      Math.max(
        point.total,
        point.failed,
      ),
    ),
    1,
  );

  const usableWidth =
    width - paddingX * 2;

  const usableHeight =
  height -
  paddingTop -
  paddingBottom;

  const points: ChartPoint[] =
    series.map(
      (point, index) => {
        const x =
          series.length === 1
            ? width / 2
            : paddingX +
              (index /
                (series.length -
                  1)) *
                usableWidth;

        return {
          x,
          total: point.total,
          failed: point.failed,
          label: formatBucket(
            point.bucketStart,
          ),
        };
      },
    );

  return {
    width,
    height,
    points,
    max,
    paddingTop,
    usableHeight,
  };
}

function ExecutionChart({
  chart,
}: {
  chart: ReturnType<typeof buildChart>;
}) {
  const {
    width,
    height,
    points,
    max,
    paddingTop,
    usableHeight,
  } = chart;

  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  const totalPoints = points.map((point) => ({
    x: point.x,
    y: height - 12 - (point.total / max) * usableHeight,
  }));

  const failedPoints = points.map((point) => ({
    x: point.x,
    y: height - 12 - (point.failed / max) * usableHeight,
  }));

  const totalPath = createLinePath(totalPoints);
  const failedPath = createLinePath(failedPoints);

  const totalArea = totalPoints.length
    ? `${totalPath} L ${totalPoints[totalPoints.length - 1].x} ${height - 12} L ${totalPoints[0].x} ${height - 12} Z`
    : "";

  const activePoint = hoveredIndex !== null ? points[hoveredIndex] : null;
  const activeTotalPoint = hoveredIndex !== null ? totalPoints[hoveredIndex] : null;
  const activeFailedPoint = hoveredIndex !== null ? failedPoints[hoveredIndex] : null;

  const handleMouseMove = (e: React.MouseEvent<SVGSVGElement>) => {
    if (points.length === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const xRatio = (e.clientX - rect.left) / rect.width;
    const closestIndex = Math.min(
      points.length - 1,
      Math.max(0, Math.round(xRatio * (points.length - 1))),
    );
    setHoveredIndex(closestIndex);
  };

  const hasAnyFailures = points.some((point) => point.failed > 0);

  return (
    <div className="relative flex h-full flex-col justify-between">
      {/* Floating Hover Tooltip */}
      {activePoint && (
        <div
          className="pointer-events-none absolute -top-2 z-20 rounded-lg border border-white/[0.12] bg-[#0a0a0c] px-3 py-2 shadow-2xl backdrop-blur-md transition-all duration-75"
          style={{
            left: `${(activePoint.x / width) * 100}%`,
            transform:
              activePoint.x > width * 0.75
                ? "translateX(-100%)"
                : activePoint.x < width * 0.25
                ? "translateX(0%)"
                : "translateX(-50%)",
          }}
        >
          <p className="font-mono text-[10px] uppercase tracking-wider text-white/40">
            {activePoint.label}
          </p>
          <div className="mt-1 flex items-center gap-3 text-xs">
            <span className="flex items-center gap-1.5 font-medium text-white/90">
              <span className="size-1.5 rounded-full bg-indigo-400" />
              {activePoint.total.toLocaleString()} runs
            </span>
            <span className="flex items-center gap-1.5 font-medium text-red-400">
              <span className="size-1.5 rounded-full bg-red-400" />
              {activePoint.failed.toLocaleString()} failed
            </span>
          </div>
        </div>
      )}

      {/* Responsive SVG Chart */}
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-full w-full cursor-crosshair overflow-visible"
        onMouseMove={handleMouseMove}
        onMouseLeave={() => setHoveredIndex(null)}
      >
        <defs>
          <linearGradient id="runs-area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgb(99 102 241)" stopOpacity="0.14" />
            <stop offset="100%" stopColor="rgb(99 102 241)" stopOpacity="0" />
          </linearGradient>
        </defs>

        {/* Horizontal grid lines */}
        {[0, 1, 2, 3, 4].map((index) => {
          const y = paddingTop + (index * usableHeight) / 4;
          return (
            <line
              key={index}
              x1="12"
              x2={width - 12}
              y1={y}
              y2={y}
              stroke="rgba(255,255,255,0.04)"
              strokeWidth="1"
              vectorEffect="non-scaling-stroke"
            />
          );
        })}

        {/* Area fill */}
        {totalArea && (
          <path d={totalArea} fill="url(#runs-area)" />
        )}

        {/* Total runs line */}
        {totalPath && (
          <path
            d={totalPath}
            fill="none"
            stroke="rgb(129 140 248)"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        )}

        {/* Failed runs line */}
        {hasAnyFailures && failedPath && (
          <path
            d={failedPath}
            fill="none"
            stroke="rgb(248 113 113)"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        )}

        {/* Vertical hover indicator line */}
        {activePoint && (
          <line
            x1={activePoint.x}
            x2={activePoint.x}
            y1={paddingTop}
            y2={height - 12}
            stroke="rgba(255,255,255,0.2)"
            strokeDasharray="3 3"
            strokeWidth="1"
            vectorEffect="non-scaling-stroke"
          />
        )}

        {/* Points or active highlighted point */}
        {activeTotalPoint && (
          <circle
            cx={activeTotalPoint.x}
            cy={activeTotalPoint.y}
            r="4"
            fill="rgb(129 140 248)"
            stroke="#0a0a0c"
            strokeWidth="2"
          />
        )}

        {activeFailedPoint && activePoint && activePoint.failed > 0 && (
          <circle
            cx={activeFailedPoint.x}
            cy={activeFailedPoint.y}
            r="3.5"
            fill="rgb(248 113 113)"
            stroke="#0a0a0c"
            strokeWidth="2"
          />
        )}
      </svg>

      {/* Axis Labels: First, Middle, and Last bucket */}
      {points.length > 0 && (
        <div className="mt-2 flex items-center justify-between border-t border-white/[0.045] pt-2 text-[10px] font-mono uppercase tracking-wider text-white/40">
          <span>{points[0]?.label ?? "—"}</span>
          <span>{points[Math.floor((points.length - 1) / 2)]?.label ?? ""}</span>
          <span>{points[points.length - 1]?.label ?? "—"}</span>
        </div>
      )}
    </div>
  );
}

function createLinePath(
  points: Array<{
    x: number;
    y: number;
  }>,
) {
  if (!points.length) {
    return "";
  }

  return points
    .map(
      (point, index) =>
        `${index === 0 ? "M" : "L"} ${point.x} ${point.y}`,
    )
    .join(" ");
}

/* ==========================================================================
   RELIABILITY
============================================================================ */

function ReliabilityCard({
  loading,
  successRate,
  failedRuns,
  activeRuns,
  anomalies,
  totalRuns,
}: {
  loading: boolean;
  successRate: number;
  failedRuns: number;
  activeRuns: number;
  anomalies: number;
  totalRuns: number;
}) {
  const hasNoRuns = totalRuns === 0;

  const clamped = hasNoRuns
    ? 0
    : Math.max(
        0,
        Math.min(
          100,
          successRate,
        ),
      );

  const radius = 42;

  const circumference =
    2 * Math.PI * radius;

  const offset = hasNoRuns
    ? circumference
    : circumference -
      (clamped / 100) *
        circumference;

  return (
    <Card className="h-full overflow-hidden rounded-xl border-white/[0.065] bg-[#0a0a0c] shadow-none">
      <CardContent className="p-0">

        <div className="border-b border-white/[0.055] px-5 py-4">
          <h2 className="text-sm font-medium text-white/80">
            Reliability
          </h2>

          <p className="mt-1 text-[11px] text-white/45">
            Execution health
          </p>
        </div>

        <div className="px-5 py-5">

          <div className="flex items-center gap-5">
            <div className="relative size-[112px] shrink-0">
              <svg
                viewBox="0 0 112 112"
                className="size-full -rotate-90"
              >
                <circle
                  cx="56"
                  cy="56"
                  r={radius}
                  fill="none"
                  stroke="rgba(255,255,255,0.055)"
                  strokeWidth="9"
                />

                {!loading && (
                  <circle
                    cx="56"
                    cy="56"
                    r={radius}
                    fill="none"
                    stroke={
                      hasNoRuns
                        ? "rgba(255,255,255,0.12)"
                        : getHealthStroke(successRate)
                    }
                    strokeWidth="9"
                    strokeLinecap="round"
                    strokeDasharray={
                      circumference
                    }
                    strokeDashoffset={
                      offset
                    }
                    className="transition-all duration-500"
                  />
                )}
              </svg>

              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-[19px] font-semibold tracking-[-0.04em] text-white">
                  {loading || hasNoRuns
                    ? "—"
                    : `${successRate.toFixed(
                        1,
                      )}%`}
                </span>

                <span className="mt-0.5 font-mono text-[7px] uppercase tracking-[0.12em] text-white/35">
                  {hasNoRuns ? "no data" : "success"}
                </span>
              </div>
            </div>

            <div>
              <p className="text-sm font-medium text-white/70">
                {loading
                  ? "Loading"
                  : hasNoRuns
                    ? "No data"
                    : getHealthLabel(
                        successRate,
                      )}
              </p>

              <p className="mt-1 text-[11px] leading-5 text-white/40">
                {hasNoRuns
                  ? "No workflow runs recorded"
                  : "Workflow execution reliability"}
              </p>
            </div>
          </div>

          <div className="mt-6 border-t border-white/[0.05]">

            <ReliabilityRow
              label="Succeeded"
              value={
                loading || hasNoRuns
                  ? "—"
                  : `${successRate.toFixed(
                      1,
                    )}%`
              }
              tone={hasNoRuns ? "neutral" : "success"}
            />

            <ReliabilityRow
              label="Failed"
              value={
                loading
                  ? "—"
                  : hasNoRuns
                    ? "—"
                    : failedRuns.toLocaleString()
              }
              tone={
                hasNoRuns
                  ? "neutral"
                  : failedRuns > 0
                    ? "danger"
                    : "neutral"
              }
            />

            <ReliabilityRow
              label="Active"
              value={
                loading
                  ? "—"
                  : activeRuns.toLocaleString()
              }
              tone={
                activeRuns > 0
                  ? "active"
                  : "neutral"
              }
            />

            <ReliabilityRow
              label="Open anomalies"
              value={loading ? "—" : anomalies.toLocaleString()}
              tone={
                anomalies > 0
                  ? "warning"
                  : "neutral"
              }
            />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ==========================================================================
   RELIABILITY ROW
============================================================================ */

function ReliabilityRow({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone:
    | "success"
    | "danger"
    | "warning"
    | "active"
    | "neutral";
}) {
  return (
    <div className="flex items-center justify-between border-b border-white/[0.045] py-3 last:border-b-0">
      <div className="flex items-center gap-2">
        <span
          className={cn(
            "size-1.5 rounded-full",
            tone === "success" &&
              "bg-emerald-400/75",
            tone === "danger" &&
              "bg-red-400/70",
            tone === "warning" &&
              "bg-amber-400/70",
            tone === "active" &&
              "bg-indigo-400/70",
            tone === "neutral" &&
              "bg-white/15",
          )}
        />

        <span className="text-[11px] text-white/32">
          {label}
        </span>
      </div>

      <span className="text-[11px] tabular-nums text-white/55">
        {value}
      </span>
    </div>
  );
}

/* ==========================================================================
   EXECUTION TABLE
============================================================================ */

function ExecutionTable({
  executions,
}: {
  executions: ExecutionSummary[];
}) {
  return (
    <div className="overflow-x-auto">
      <div className="min-w-[760px]">

        {/* HEADER */}

        <div className="grid grid-cols-[minmax(280px,1.8fr)_110px_90px_110px_100px] border-b border-white/[0.045] px-5 py-2.5">
          <TableHeader>
            Workflow
          </TableHeader>

          <TableHeader>
            Status
          </TableHeader>

          <TableHeader>
            Duration
          </TableHeader>

          <TableHeader>
            Started
          </TableHeader>

          <TableHeader>
            Trigger
          </TableHeader>
        </div>

        {/* ROWS */}

        {executions.map(
          execution => (
            <Link
              key={execution.id}
              href={`/executions/${execution.id}`}
              className="group grid grid-cols-[minmax(280px,1.8fr)_110px_90px_110px_100px] items-center border-b border-white/[0.04] px-5 py-3.5 transition-colors last:border-b-0 hover:bg-white/[0.018]"
            >
              {/* WORKFLOW */}

              <div className="min-w-0">
                <p className="truncate text-[12px] font-medium text-white/70 transition-colors group-hover:text-white">
                  {execution.workflowName ??
                    "Untitled workflow"}
                </p>

                <p className="mt-1 truncate font-mono text-[11px] text-white/45">
                  v
                  {
                    execution.versionNumber
                  }{" "}
                  ·{" "}
                  {execution.id.slice(
                    0,
                    10,
                  )}
                </p>
              </div>

              {/* STATUS */}

              <div>
                <StatusBadge
                  status={
                    execution.status
                  }
                />
              </div>

              {/* DURATION */}

              <span className="text-[11px] tabular-nums text-white/50">
                {execution.durationMs !==
                null
                  ? formatDuration(
                      execution.durationMs,
                    )
                  : "—"}
              </span>

              {/* STARTED */}

              <span className="text-[11px] text-white/45">
                {formatRelativeTime(
                  execution.createdAt,
                )}
              </span>

              {/* TRIGGER */}

              <span className="truncate text-[11px] text-white/45">
                {formatTrigger(
                  execution.triggerType,
                )}
              </span>
            </Link>
          ),
        )}
      </div>
    </div>
  );
}

/* ==========================================================================
   DASHBOARD LINKS
============================================================================ */

function DashboardLink({
  href,
  title,
  description,
}: {
  href: string;
  title: string;
  description: string;
}) {
  return (
    <Link
      href={href}
      className="group block rounded-xl border border-white/[0.065] bg-[#0a0a0c] px-4 py-3.5 transition-colors hover:border-white/[0.12] hover:bg-white/[0.03]"
    >
      <div className="flex items-center justify-between">
        <span className="text-[12px] font-medium text-white/70 transition-colors group-hover:text-white">
          {title}
        </span>

        <ArrowRight className="size-3 text-white/40 transition-all group-hover:translate-x-0.5 group-hover:text-white/70" />
      </div>

      <p className="mt-1 text-[11px] text-white/45">
        {description}
      </p>
    </Link>
  );
}

/* ==========================================================================
   CHART LEGEND
============================================================================ */

function ChartLegend({
  label,
  color,
}: {
  label: string;
  color: string;
}) {
  return (
    <div className="flex items-center gap-1.5">
      <span
        className={cn(
          "h-1.5 w-3 rounded-full",
          color,
        )}
      />

      <span className="text-[11px] text-white/45">
        {label}
      </span>
    </div>
  );
}

/* ==========================================================================
   LOADING
============================================================================ */

function ChartSkeleton() {
  return (
    <div className="flex h-full flex-col justify-end gap-3 pb-8">
      <Skeleton className="h-px w-full bg-white/[0.045]" />
      <Skeleton className="h-px w-full bg-white/[0.045]" />
      <Skeleton className="h-px w-full bg-white/[0.045]" />
      <Skeleton className="h-20 w-full rounded-lg bg-white/[0.02]" />
    </div>
  );
}

function ExecutionTableSkeleton() {
  return (
    <div className="min-w-[760px]">
      {Array.from(
        { length: 5 },
        (_, index) => (
          <div
            key={index}
            className="grid grid-cols-[minmax(280px,1.8fr)_110px_90px_110px_100px] items-center gap-3 border-b border-white/[0.04] px-5 py-3.5"
          >
            <div>
              <Skeleton className="h-3.5 w-36 bg-white/[0.045]" />
              <Skeleton className="mt-1.5 h-2.5 w-24 bg-white/[0.025]" />
            </div>

            <Skeleton className="h-5 w-16 rounded-md bg-white/[0.04]" />

            <Skeleton className="h-3 w-10 bg-white/[0.03]" />

            <Skeleton className="h-3 w-14 bg-white/[0.03]" />

            <Skeleton className="h-3 w-14 bg-white/[0.03]" />
          </div>
        ),
      )}
    </div>
  );
}

/* ==========================================================================
   EMPTY / ERROR
============================================================================ */

function EmptyChart() {
  return (
    <div className="flex h-full items-center justify-center">
      <div className="text-center">
        <Activity className="mx-auto size-5 text-white/30" />

        <p className="mt-2 text-xs font-medium text-white/60">
          No execution activity
        </p>

        <p className="mt-1 text-[11px] text-white/45">
          Activity will appear here once
          workflows run.
        </p>
      </div>
    </div>
  );
}

function EmptyExecutions() {
  return (
    <div className="flex min-h-[180px] flex-col items-center justify-center px-5 text-center">
      <div className="flex size-10 items-center justify-center rounded-lg border border-white/[0.07] bg-[#111114]">
        <Activity className="size-4 text-white/40" />
      </div>

      <p className="mt-3 text-sm font-medium text-white/70">
        No executions yet
      </p>

      <p className="mt-1 text-[11px] text-white/45">
        Run a workflow to start seeing
        activity here.
      </p>
    </div>
  );
}

function QueryError({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="flex min-h-[160px] flex-col items-center justify-center px-5 text-center">
      <p className="text-sm text-white/45">
        {message}
      </p>

      <button
        type="button"
        onClick={onRetry}
        className="mt-3 text-[11px] text-white/35 underline underline-offset-4 hover:text-white/70"
      >
        Retry
      </button>
    </div>
  );
}

/* ==========================================================================
   HELPERS
============================================================================ */

function getRangeLabel(
  range: StatsRange,
) {
  switch (range) {
    case "24h":
      return "Today";

    case "7d":
      return "Last 7 days";

    case "30d":
      return "Last 30 days";

    case "90d":
      return "Last 90 days";
  }
}

function formatBucket(
  value: string,
) {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "—";
  }

  return date.toLocaleDateString(
    undefined,
    {
      month: "short",
      day: "numeric",
    },
  );
}

function formatTrigger(
  value: string,
) {
  if (!value) {
    return "—";
  }

  return value
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(
      /\b\w/g,
      character =>
        character.toUpperCase(),
    );
}

function TableHeader({
  children,
}: {
  children: ReactNode;
}) {
  return (
    <span className="text-[10px] font-medium uppercase tracking-[0.1em] text-white/40">
      {children}
    </span>
  );
}