"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { MoreHorizontal, Search, ShieldCheck } from "lucide-react";
import { toast } from "sonner";
import { format, formatDistanceToNowStrict, isValid } from "date-fns";

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

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

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

const ALL = "all" as const;

type StatusFilter = AnomalyStatus | typeof ALL;

const REASON_MAX_LENGTH = 500;

const SEVERITY: Record<
  AnomalySeverity,
  { label: string; dot: string; text: string }
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
  { label: string; className: string }
> = {
  OPEN: {
    label: "Open",
    className: "bg-blue-500/10 text-blue-400 border-blue-500/20",
  },
  ACKNOWLEDGED: {
    label: "Acknowledged",
    className: "bg-purple-500/10 text-purple-400 border-purple-500/20",
  },
  VERIFYING_RECOVERY: {
    label: "Verifying recovery",
    className: "bg-amber-500/10 text-amber-400 border-amber-500/20",
  },
  RESOLVED: {
    label: "Resolved",
    className: "bg-green-500/10 text-green-400 border-green-500/20",
  },
  FALSE_POSITIVE: {
    label: "False positive",
    className: "bg-gray-500/10 text-gray-400 border-gray-500/20",
  },
};

const STATUS_OPTIONS = Object.keys(STATUS) as AnomalyStatus[];

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
      <span className="text-white/44">
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
      {config.label}
    </span>
  );
}

function DetectedAt({
  value,
}: {
  value: string;
}) {
  const date = new Date(value);

  if (!isValid(date)) {
    return (
      <span className="text-white/45">
        —
      </span>
    );
  }

  return (
    <time
      dateTime={date.toISOString()}
      title={format(date, "MMM d, yyyy 'at' HH:mm")}
      className="whitespace-nowrap"
    >
      {formatDistanceToNowStrict(date, {
        addSuffix: true,
      })}
    </time>
  );
}

/* -------------------------------------------------------------------------- */
/* Page                                                                       */
/* -------------------------------------------------------------------------- */

export default function AnomaliesPage() {
  const [anomalies, setAnomalies] = useState<AnomalySummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [statusFilter, setStatusFilter] =
    useState<StatusFilter>(ALL);

  const [searchTerm, setSearchTerm] = useState("");
  const [pageSize, setPageSize] = useState(25);

  // Row-level pending state, so one action doesn't lock the whole table.
  const [pendingId, setPendingId] = useState<string | null>(null);

  // False-positive dialog
  const [fpTarget, setFpTarget] =
    useState<AnomalySummary | null>(null);

  const [fpReason, setFpReason] = useState("");
  const [fpSubmitting, setFpSubmitting] = useState(false);

  // Guards against out-of-order responses when filters change quickly.
  const requestId = useRef(0);

  const loadAnomalies = useCallback(
    async (options?: { silent?: boolean }) => {
      const id = ++requestId.current;

      if (!options?.silent) {
        setLoading(true);
      }

      setError(null);

      try {
        const response: AnomaliesResponse =
          await fetchAnomalies(
            statusFilter === ALL
              ? undefined
              : { status: statusFilter }
          );

        if (id !== requestId.current) {
          return;
        }

        setAnomalies(response.anomalies ?? []);
      } catch (err) {
        if (id !== requestId.current) {
          return;
        }

        console.error("Failed to load anomalies", err);

        setError(
          "We couldn't load anomalies. Check your connection and try again."
        );
      } finally {
        if (id === requestId.current) {
          setLoading(false);
        }
      }
    },
    [statusFilter]
  );

  useEffect(() => {
    void loadAnomalies();
    const reqRef = requestId;

    return () => {
      // Invalidate any in-flight request on filter change / unmount.
      reqRef.current++;
    };
  }, [loadAnomalies]);

  const visibleAnomalies = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();

    const matches = query
      ? anomalies.filter((a) =>
          [
            a.workflowName,
            a.nodeId,
            a.type,
            a.metric,
          ].some((field) =>
            field?.toLowerCase().includes(query)
          )
        )
      : anomalies;

    return [...matches].sort(
      (a, b) =>
        new Date(b.detectedAt).getTime() -
        new Date(a.detectedAt).getTime()
    );
  }, [anomalies, searchTerm]);

  const isFiltered =
    searchTerm.trim() !== "" ||
    statusFilter !== ALL;

  const isInitialLoad =
    loading && anomalies.length === 0;

  function clearFilters() {
    setSearchTerm("");
    setStatusFilter(ALL);
  }

  async function runAction(
    action: (id: string) => Promise<unknown>,
    id: string,
    successMessage: string
  ) {
    setPendingId(id);

    try {
      await action(id);

      toast.success(successMessage);

      await loadAnomalies({
        silent: true,
      });
    } catch (err) {
      console.error(err);

      toast.error(
        "Couldn't update the anomaly. Try again."
      );
    } finally {
      setPendingId(null);
    }
  }

  function closeFalsePositiveDialog() {
    setFpTarget(null);
    setFpReason("");
  }

  async function submitFalsePositive(
    e: React.FormEvent
  ) {
    e.preventDefault();

    const reason = fpReason.trim();

    if (!fpTarget || !reason || fpSubmitting) {
      return;
    }

    setFpSubmitting(true);

    try {
      await markAnomalyFalsePositive(
        fpTarget.id,
        reason
      );

      toast.success(
        "Marked as false positive"
      );

      closeFalsePositiveDialog();

      await loadAnomalies({
        silent: true,
      });
    } catch (err) {
      console.error(err);

      toast.error(
        "Couldn't update the anomaly. Try again."
      );
    } finally {
      setFpSubmitting(false);
    }
  }

  const counts = useMemo(() => {
    const openCount = anomalies.filter((a) => a.status === "OPEN").length;
    const criticalCount = anomalies.filter(
      (a) =>
        (a.status === "OPEN" || a.status === "ACKNOWLEDGED") &&
        (a.severity === "CRITICAL" || a.severity === "HIGH"),
    ).length;
    const verifyingCount = anomalies.filter(
      (a) => a.status === "VERIFYING_RECOVERY",
    ).length;
    const resolvedCount = anomalies.filter(
      (a) => a.status === "RESOLVED",
    ).length;
    return { openCount, criticalCount, verifyingCount, resolvedCount };
  }, [anomalies]);

  return (
    <PageContainer variant="wide">
      {/* Header */}
      <PageHeader
        eyebrow="Reliability"
        title="Anomalies"
        description="Unusual behavior detected in your workflow runs."
      />

      {/* KPI Strip */}
      <div className="mb-6">
        <KpiStrip
          items={[
            {
              label: "Open anomalies",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                counts.openCount.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : anomalies.length === 0
                  ? "No anomalies recorded"
                  : counts.openCount === 0
                    ? "All systems normal"
                    : "Requires investigation",
              tone:
                anomalies.length === 0 || counts.openCount === 0
                  ? "neutral"
                  : "danger",
            },
            {
              label: "High severity",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                counts.criticalCount.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : anomalies.length === 0
                  ? "No anomalies recorded"
                  : counts.criticalCount === 0
                    ? "No critical items"
                    : "High priority",
              tone:
                anomalies.length === 0 || counts.criticalCount === 0
                  ? "neutral"
                  : "danger",
            },
            {
              label: "Verifying recovery",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                counts.verifyingCount.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : anomalies.length === 0
                  ? "No active verifications"
                  : counts.verifyingCount > 0
                    ? "Observing test runs"
                    : "None active",
              tone: counts.verifyingCount > 0 ? "warning" : "neutral",
            },
            {
              label: "Resolved",
              value: loading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                counts.resolvedCount.toLocaleString()
              ),
              detail: loading
                ? "Loading"
                : anomalies.length === 0
                  ? "No resolved anomalies"
                  : counts.resolvedCount > 0
                    ? "Verified healthy"
                    : "None resolved",
              tone: counts.resolvedCount > 0 ? "success" : "neutral",
            },
          ]}
        />
      </div>

      {/* Toolbar */}
      <Toolbar className="mb-6">
        <div className="relative min-w-0 flex-1">
          <Label
            htmlFor="anomaly-search"
            className="sr-only"
          >
            Search anomalies
          </Label>

          <Search
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-white/40"
            aria-hidden
          />

          <Input
            id="anomaly-search"
            type="search"
            placeholder="Search workflow, node, or type..."
            value={searchTerm}
            onChange={(e) =>
              setSearchTerm(e.target.value)
            }
            className="pl-10 text-sm"
          />
        </div>

        <Select
          value={statusFilter}
          onValueChange={(value) =>
            setStatusFilter(
              value as StatusFilter
            )
          }
        >
          <SelectTrigger
            className="w-full sm:w-[180px]"
            aria-label="Filter by status"
          >
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>

          <SelectContent>
            <SelectItem value={ALL}>
              All statuses
            </SelectItem>

            {STATUS_OPTIONS.map((status) => (
              <SelectItem
                key={status}
                value={status}
              >
                {STATUS[status].label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Toolbar>

      {/* Error when data exists */}
      {error && anomalies.length > 0 && (
        <ErrorState
          title="Unable to refresh anomalies"
          message={error}
          onRetry={() => void loadAnomalies()}
          className="mb-6"
        />
      )}

      {/* Main Content */}
      {error && anomalies.length === 0 ? (
        <ErrorState
          title="Unable to load anomalies"
          message={error}
          onRetry={() => void loadAnomalies()}
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-white/[0.065] bg-[#0a0a0c]">
          {isInitialLoad ? (
            <TableSkeleton rows={6} />
          ) : visibleAnomalies.length === 0 ? (
            <EmptyState
              icon={ShieldCheck}
              title={
                isFiltered
                  ? "No anomalies match your filters"
                  : "No anomalies detected"
              }
              description={
                isFiltered
                  ? "Try changing your status filter or search term."
                  : "Detection starts once your workflows have enough run history to establish a baseline."
              }
              actions={
                isFiltered ? (
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
            <div
              className={`overflow-x-auto transition-opacity ${
                loading
                  ? "opacity-60"
                  : "opacity-100"
              }`}
              aria-busy={loading}
            >
              <Table>
                <TableHeader className="sticky top-0 z-10 bg-[#0c0c0e]/95 backdrop-blur-sm">
                  <TableRow className="hover:bg-transparent">
                    <TableHead>
                      Workflow
                    </TableHead>

                    <TableHead className="hidden lg:table-cell">
                      Type
                    </TableHead>

                    <TableHead>
                      Severity
                    </TableHead>

                    <TableHead>
                      Status
                    </TableHead>

                    <TableHead className="hidden md:table-cell">
                      Detected
                    </TableHead>

                    <TableHead className="w-12">
                      <span className="sr-only">
                        Actions
                      </span>
                    </TableHead>
                  </TableRow>
                </TableHeader>

                <TableBody>
                  {visibleAnomalies.slice(0, pageSize).map(
                    (anomaly) => {
                      const rowPending =
                        pendingId === anomaly.id;

                      const canAcknowledge =
                        anomaly.status === "OPEN";

                      const canResolve =
                        anomaly.status === "OPEN" ||
                        anomaly.status ===
                          "ACKNOWLEDGED";

                      const canDismiss =
                        anomaly.status === "OPEN";

                      return (
                        <TableRow
                          key={anomaly.id}
                          className="hover:bg-white/[0.03]"
                          aria-busy={rowPending}
                        >
                          <TableCell>
                            <Link
                              href={`/workflows/${anomaly.workflowId}`}
                              title={anomaly.workflowName}
                              className="block max-w-[200px] truncate font-medium text-white/90 hover:underline focus-visible:underline focus-visible:outline-none sm:max-w-xs"
                            >
                              {anomaly.workflowName}
                            </Link>

                            {anomaly.nodeId && (
                              <p
                                title={anomaly.nodeId}
                                className="mt-0.5 truncate font-mono text-xs text-white/44"
                              >
                                {anomaly.nodeId}
                              </p>
                            )}
                          </TableCell>

                          <TableCell className="hidden lg:table-cell">
                            <p className="text-sm text-white/70">
                              {anomaly.type}
                            </p>

                            {anomaly.metric && (
                              <p
                                title={anomaly.metric}
                                className="mt-0.5 max-w-[180px] truncate text-xs text-white/44"
                              >
                                {anomaly.metric}
                              </p>
                            )}
                          </TableCell>

                          <TableCell>
                            <SeverityLabel
                              severity={
                                anomaly.severity
                              }
                            />
                          </TableCell>

                        <TableCell>
                          <StatusBadge
                            status={
                              anomaly.status
                            }
                          />
                        </TableCell>

                        <TableCell className="hidden text-sm text-white/44 md:table-cell">
                          <DetectedAt
                            value={
                              anomaly.detectedAt
                            }
                          />
                        </TableCell>

                        <TableCell className="text-right">
                          <DropdownMenu>
                            <DropdownMenuTrigger
                              asChild
                            >
                              <Button
                                variant="ghost"
                                size="icon"
                                className="size-8"
                                disabled={
                                  rowPending
                                }
                                aria-label={`Actions for ${anomaly.workflowName}`}
                              >
                                <MoreHorizontal className="size-4" />
                              </Button>
                            </DropdownMenuTrigger>

                            <DropdownMenuContent align="end">
                              <DropdownMenuItem
                                asChild
                              >
                                <Link
                                  href={`/reliability/${anomaly.id}`}
                                >
                                  View details
                                </Link>
                              </DropdownMenuItem>

                              {(
                                canAcknowledge ||
                                canResolve ||
                                canDismiss
                              ) && (
                                <DropdownMenuSeparator />
                              )}

                              {canAcknowledge && (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    void runAction(
                                      acknowledgeAnomaly,
                                      anomaly.id,
                                      "Anomaly acknowledged"
                                    )
                                  }
                                >
                                  Acknowledge
                                </DropdownMenuItem>
                              )}

                              {canResolve && (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    void runAction(
                                      resolveAnomaly,
                                      anomaly.id,
                                      "Anomaly resolved"
                                    )
                                  }
                                >
                                  Resolve
                                </DropdownMenuItem>
                              )}

                              {canDismiss && (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    setFpTarget(
                                      anomaly
                                    )
                                  }
                                >
                                  Mark as false positive
                                </DropdownMenuItem>
                              )}
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </TableCell>
                      </TableRow>
                    );
                  }
                )}
              </TableBody>
            </Table>
          </div>
        )}

        {!isInitialLoad &&
          visibleAnomalies.length > 0 && (
            <div className="border-t border-white/[0.08] px-4 py-3 text-xs text-white/44">
              {isFiltered
                ? `Showing ${visibleAnomalies.length} of ${anomalies.length}`
                : `${anomalies.length} ${
                    anomalies.length === 1
                      ? "anomaly"
                      : "anomalies"
                  }`}
            </div>
          )}
      </div>
      )}

      <LoadMoreButton
        loadedCount={Math.min(pageSize, visibleAnomalies.length)}
        totalCount={visibleAnomalies.length}
        onLoadMore={() => setPageSize((prev) => prev + 25)}
      />

      {/* False positive dialog */}
      <Dialog
        open={fpTarget !== null}
        onOpenChange={(open) => {
          if (!open && !fpSubmitting) {
            closeFalsePositiveDialog();
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
                {fpTarget
                  ? `Explain why this anomaly on ${fpTarget.workflowName} isn't a real issue. The reason is saved to the audit trail and helps the detector avoid repeating it.`
                  : "Explain why this anomaly isn't a real issue."}
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
                maxLength={REASON_MAX_LENGTH}
                onChange={(e) =>
                  setFpReason(e.target.value)
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
                  closeFalsePositiveDialog
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