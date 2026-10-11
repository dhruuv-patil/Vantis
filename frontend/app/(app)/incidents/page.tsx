"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  ExternalLink,
  MoreHorizontal,
  Search,
  ShieldCheck,
} from "lucide-react";
import { toast } from "sonner";
import { format, formatDistanceToNowStrict, isValid } from "date-fns";

import {
  acknowledgeIncident,
  fetchIncidents,
  getErrorMessage,
  resolveIncident,
} from "@/lib/api";

import type { Incident, IncidentStatus, AnomalySeverity } from "@/types";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import {
  EmptyState,
  ErrorState,
  KpiStrip,
  LoadMoreButton,
  MetricItem,
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

const ALL = "all" as const;
type StatusFilter = IncidentStatus | typeof ALL;
type SeverityFilter = AnomalySeverity | typeof ALL;

const SEVERITY_CONFIG: Record<
  AnomalySeverity,
  { label: string; dot: string; text: string }
> = {
  LOW: {
    label: "Low",
    dot: "bg-green-500 dark:bg-green-400",
    text: "text-emerald-700 dark:text-white/60",
  },
  MEDIUM: {
    label: "Medium",
    dot: "bg-yellow-500 dark:bg-yellow-400",
    text: "text-amber-700 dark:text-white/70",
  },
  HIGH: {
    label: "High",
    dot: "bg-orange-500 dark:bg-orange-400",
    text: "text-orange-700 dark:text-white/80",
  },
  CRITICAL: {
    label: "Critical",
    dot: "bg-red-500 dark:bg-red-400",
    text: "text-red-700 dark:text-red-300",
  },
};

const STATUS_CONFIG: Record<
  IncidentStatus,
  { label: string; className: string }
> = {
  OPEN: {
    label: "Open",
    className:
      "bg-red-500/10 text-red-600 dark:text-red-400 border-red-500/20",
  },
  ACKNOWLEDGED: {
    label: "Acknowledged",
    className:
      "bg-purple-500/10 text-purple-600 dark:text-purple-400 border-purple-500/20",
  },
  RESOLVED: {
    label: "Resolved",
    className:
      "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
  },
};

function SeverityLabel({ severity }: { severity: AnomalySeverity }) {
  const config = SEVERITY_CONFIG[severity];

  if (!config) {
    return (
      <span className="text-muted-foreground dark:text-white/44">
        {severity}
      </span>
    );
  }

  return (
    <span className={`inline-flex items-center gap-2 text-sm ${config.text}`}>
      <span className={`size-1.5 rounded-full ${config.dot}`} aria-hidden />
      {config.label}
    </span>
  );
}

function DetectedAt({ value }: { value: string }) {
  const date = new Date(value);

  if (!isValid(date)) {
    return (
      <span className="text-muted-foreground dark:text-white/45">—</span>
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

export default function IncidentsPage() {
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(ALL);
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>(ALL);
  const [pageSize, setPageSize] = useState(25);
  const [recoveryBlockModalOpen, setRecoveryBlockModalOpen] = useState(false);
  const [blockedIncident, setBlockedIncident] = useState<Incident | null>(null);

  const incidentsQuery = useQuery({
    queryKey: ["incidents"],
    queryFn: () => fetchIncidents(),
    refetchInterval: 15_000,
  });

  const acknowledgeMutation = useMutation({
    mutationFn: (id: string) => acknowledgeIncident(id),
    onSuccess: () => {
      toast.success("Incident acknowledged");
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
    onError: (err) => {
      toast.error(getErrorMessage(err) || "Failed to acknowledge incident");
    },
  });

  const resolveMutation = useMutation({
    mutationFn: (id: string) => resolveIncident(id),
    onSuccess: () => {
      toast.success("Incident resolved");
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
    onError: (err: any, incidentId) => {
      const msg = getErrorMessage(err);
      if (
        msg.includes("recovery is verified") ||
        msg.includes("ANOMALY_ALREADY_CLOSED") ||
        err?.response?.status === 409 ||
        err?.status === 409 ||
        err?.response?.data?.code === "RECOVERY_UNVERIFIED"
      ) {
        const inc = (incidentsQuery.data || []).find((i) => i.id === incidentId);
        setBlockedIncident(inc || null);
        setRecoveryBlockModalOpen(true);
      } else {
        toast.error(msg || "Failed to resolve incident");
      }
    },
  });

  const incidents = useMemo(() => incidentsQuery.data ?? [], [incidentsQuery.data]);

  const counts = useMemo(() => {
    const openCount = incidents.filter((i) => i.status === "OPEN").length;
    const criticalCount = incidents.filter(
      (i) =>
        (i.status === "OPEN" || i.status === "ACKNOWLEDGED") &&
        (i.severity === "CRITICAL" || i.severity === "HIGH"),
    ).length;
    const acknowledgedCount = incidents.filter(
      (i) => i.status === "ACKNOWLEDGED",
    ).length;
    const resolvedCount = incidents.filter(
      (i) => i.status === "RESOLVED",
    ).length;

    return { openCount, criticalCount, acknowledgedCount, resolvedCount };
  }, [incidents]);

  const kpis: MetricItem[] = useMemo(() => {
    const isPending = incidentsQuery.isPending;
    const hasNone = incidents.length === 0;

    return [
      {
        label: "Active incidents",
        value: isPending ? (
          <Skeleton className="h-7 w-14" />
        ) : (
          counts.openCount.toLocaleString()
        ),
        detail: isPending
          ? "Loading"
          : hasNone
            ? "No incidents recorded"
            : counts.openCount === 0
              ? "All workflows normal"
              : "Requires investigation",
        tone:
          hasNone || counts.openCount === 0
            ? "neutral"
            : "danger",
      },
      {
        label: "Critical / High",
        value: isPending ? (
          <Skeleton className="h-7 w-14" />
        ) : (
          counts.criticalCount.toLocaleString()
        ),
        detail: isPending
          ? "Loading"
          : hasNone
            ? "No incidents recorded"
            : counts.criticalCount === 0
              ? "No critical items"
              : "High priority",
        tone:
          hasNone || counts.criticalCount === 0
            ? "neutral"
            : "danger",
      },
      {
        label: "Acknowledged",
        value: isPending ? (
          <Skeleton className="h-7 w-14" />
        ) : (
          counts.acknowledgedCount.toLocaleString()
        ),
        detail: isPending
          ? "Loading"
          : hasNone
            ? "No active investigations"
            : counts.acknowledgedCount > 0
              ? "Under investigation"
              : "None active",
        tone: counts.acknowledgedCount > 0 ? "warning" : "neutral",
      },
      {
        label: "Resolved",
        value: isPending ? (
          <Skeleton className="h-7 w-14" />
        ) : (
          counts.resolvedCount.toLocaleString()
        ),
        detail: isPending
          ? "Loading"
          : hasNone
            ? "No resolved incidents"
            : counts.resolvedCount > 0
              ? "Verified healthy"
              : "None resolved",
        tone: counts.resolvedCount > 0 ? "success" : "neutral",
      },
    ];
  }, [counts, incidents.length, incidentsQuery.isPending]);

  const visibleIncidents = useMemo(() => {
    const query = search.trim().toLowerCase();

    const matches = query
      ? incidents.filter((inc) =>
          [
            inc.title,
            inc.description,
            inc.fingerprint,
            inc.workflowId,
          ].some((field) => field?.toLowerCase().includes(query)),
        )
      : incidents;

    return matches
      .filter((inc) => {
        if (statusFilter !== ALL && inc.status !== statusFilter) return false;
        if (severityFilter !== ALL && inc.severity !== severityFilter) return false;
        return true;
      })
      .sort(
        (a, b) =>
          new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      );
  }, [incidents, search, statusFilter, severityFilter]);

  const isFiltered =
    search.trim() !== "" ||
    statusFilter !== ALL ||
    severityFilter !== ALL;

  const isInitialLoad = incidentsQuery.isPending && incidents.length === 0;

  function clearFilters() {
    setSearch("");
    setStatusFilter(ALL);
    setSeverityFilter(ALL);
  }

  return (
    <PageContainer variant="wide">
      {/* Header */}
      <PageHeader
        eyebrow="Reliability"
        title="Incidents"
        description="Unified reliability incident tracking for automated workflows. Incidents require baseline recovery verification before closing."
      />

      {/* KPI Strip */}
      <div className="mb-6">
        <KpiStrip items={kpis} />
      </div>

      {/* Toolbar */}
      <Toolbar className="mb-6">
        <div className="relative min-w-0 flex-1">
          <Label htmlFor="incident-search" className="sr-only">
            Search incidents
          </Label>

          <Search
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground dark:text-white/40"
            aria-hidden
          />

          <Input
            id="incident-search"
            type="search"
            placeholder="Search workflow, title, or fingerprint..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-10 text-sm"
          />
        </div>

        <Select
          value={statusFilter}
          onValueChange={(value) => setStatusFilter(value as StatusFilter)}
        >
          <SelectTrigger
            className="w-full sm:w-[180px]"
            aria-label="Filter by status"
          >
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>

          <SelectContent>
            <SelectItem value={ALL}>All statuses</SelectItem>
            <SelectItem value="OPEN">Open</SelectItem>
            <SelectItem value="ACKNOWLEDGED">Acknowledged</SelectItem>
            <SelectItem value="RESOLVED">Resolved</SelectItem>
          </SelectContent>
        </Select>

        <Select
          value={severityFilter}
          onValueChange={(value) => setSeverityFilter(value as SeverityFilter)}
        >
          <SelectTrigger
            className="w-full sm:w-[180px]"
            aria-label="Filter by severity"
          >
            <SelectValue placeholder="All severities" />
          </SelectTrigger>

          <SelectContent>
            <SelectItem value={ALL}>All severities</SelectItem>
            <SelectItem value="CRITICAL">Critical</SelectItem>
            <SelectItem value="HIGH">High</SelectItem>
            <SelectItem value="MEDIUM">Medium</SelectItem>
            <SelectItem value="LOW">Low</SelectItem>
          </SelectContent>
        </Select>
      </Toolbar>

      {/* Error when data exists */}
      {incidentsQuery.isError && incidents.length > 0 && (
        <ErrorState
          title="Unable to refresh incidents"
          message={getErrorMessage(incidentsQuery.error)}
          onRetry={() => incidentsQuery.refetch()}
          className="mb-6"
        />
      )}

      {/* Main Content */}
      {incidentsQuery.isError && incidents.length === 0 ? (
        <ErrorState
          title="Could not load incidents"
          message={getErrorMessage(incidentsQuery.error)}
          onRetry={() => incidentsQuery.refetch()}
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
          {isInitialLoad ? (
            <TableSkeleton rows={6} />
          ) : visibleIncidents.length === 0 ? (
            <EmptyState
              icon={ShieldCheck}
              title={
                isFiltered
                  ? "No incidents match your filters"
                  : "No incidents found"
              }
              description={
                isFiltered
                  ? "Try changing your status filter or search term."
                  : "All workflows are currently performing reliably within expected baselines."
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
                incidentsQuery.isFetching ? "opacity-60" : "opacity-100"
              }`}
              aria-busy={incidentsQuery.isFetching}
            >
              <Table>
                <TableHeader className="sticky top-0 z-10 bg-card/95 dark:bg-[#0c0c0e]/95 backdrop-blur-sm">
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-[340px]">Incident</TableHead>
                    <TableHead className="w-[110px]">Severity</TableHead>
                    <TableHead className="w-[120px]">Status</TableHead>
                    <TableHead className="hidden lg:table-cell">
                      Workflow / Anomaly
                    </TableHead>
                    <TableHead className="hidden md:table-cell w-[140px]">
                      Detected
                    </TableHead>
                    <TableHead className="w-12 text-right">
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {visibleIncidents.slice(0, pageSize).map((incident) => {
                    const stat =
                      STATUS_CONFIG[incident.status] ?? STATUS_CONFIG.OPEN;

                    return (
                      <TableRow
                        key={incident.id}
                        className="hover:bg-muted/40 dark:hover:bg-white/[0.03]"
                      >
                        <TableCell className="font-medium">
                          <div className="flex flex-col gap-1">
                            <Link
                              href={`/incidents/${incident.id}`}
                              className="hover:underline text-sm font-semibold text-foreground dark:text-white/90 line-clamp-1"
                            >
                              {incident.title}
                            </Link>
                            {incident.fingerprint && (
                              <span className="text-[11px] font-mono text-muted-foreground dark:text-white/40 line-clamp-1">
                                {incident.fingerprint}
                              </span>
                            )}
                          </div>
                        </TableCell>

                        <TableCell>
                          <SeverityLabel severity={incident.severity} />
                        </TableCell>

                        <TableCell>
                          <StatusBadge
                            status={incident.status}
                            label={stat.label}
                          />
                        </TableCell>

                        <TableCell className="hidden lg:table-cell">
                          <div className="flex flex-col gap-0.5 text-xs text-muted-foreground">
                            {(incident as any).workflowName ? (
                              <Link
                                href={`/workflows/${incident.workflowId}`}
                                className="truncate font-medium text-foreground/90 hover:underline"
                              >
                                {(incident as any).workflowName}
                              </Link>
                            ) : incident.workflowId ? (
                              <Link
                                href={`/workflows/${incident.workflowId}`}
                                className="font-mono text-[11px] text-muted-foreground hover:underline"
                              >
                                wf: {incident.workflowId.slice(0, 8)}...
                              </Link>
                            ) : null}
                            {incident.anomalyId && (
                              <Link
                                href={`/reliability/${incident.anomalyId}`}
                                className="inline-flex items-center gap-1 text-[11px] text-primary hover:underline"
                              >
                                <span>Triggering anomaly</span>
                                <ExternalLink className="size-3" />
                              </Link>
                            )}
                          </div>
                        </TableCell>

                        <TableCell className="hidden text-sm text-muted-foreground dark:text-white/44 md:table-cell">
                          <DetectedAt value={incident.createdAt} />
                        </TableCell>

                        <TableCell className="text-right">
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="size-8 text-muted-foreground/60 hover:text-foreground"
                                aria-label="Open menu"
                              >
                                <MoreHorizontal className="size-4" />
                                <span className="sr-only">Open menu</span>
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                              <DropdownMenuItem asChild>
                                <Link href={`/incidents/${incident.id}`}>
                                  View incident
                                </Link>
                              </DropdownMenuItem>
                              {incident.anomalyId && (
                                <DropdownMenuItem asChild>
                                  <Link
                                    href={`/reliability/${incident.anomalyId}`}
                                  >
                                    View linked anomaly
                                  </Link>
                                </DropdownMenuItem>
                              )}
                              <DropdownMenuSeparator />
                              {incident.status === "OPEN" && (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    acknowledgeMutation.mutate(incident.id)
                                  }
                                  onClick={() =>
                                    acknowledgeMutation.mutate(incident.id)
                                  }
                                >
                                  Acknowledge
                                </DropdownMenuItem>
                              )}
                              {incident.status !== "RESOLVED" && (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    resolveMutation.mutate(incident.id)
                                  }
                                  onClick={() =>
                                    resolveMutation.mutate(incident.id)
                                  }
                                >
                                  Resolve incident
                                </DropdownMenuItem>
                              )}
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          )}

          {!isInitialLoad && visibleIncidents.length > 0 && (
            <div className="border-t border-border dark:border-white/[0.08] px-4 py-3 text-xs text-muted-foreground dark:text-white/44">
              {isFiltered
                ? `Showing ${visibleIncidents.length} of ${incidents.length}`
                : `${incidents.length} ${
                    incidents.length === 1 ? "incident" : "incidents"
                  }`}
            </div>
          )}
        </div>
      )}

      <LoadMoreButton
        loadedCount={Math.min(pageSize, visibleIncidents.length)}
        totalCount={visibleIncidents.length}
        onLoadMore={() => setPageSize((prev) => prev + 25)}
      />

      {/* Recovery Required Alert Dialog */}
      <Dialog
        open={recoveryBlockModalOpen}
        onOpenChange={setRecoveryBlockModalOpen}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <div className="flex size-10 items-center justify-center rounded-full bg-amber-500/10 text-amber-500 mb-2">
              <AlertTriangle className="size-5" />
            </div>
            <DialogTitle>Recovery Verification Required</DialogTitle>
            <DialogDescription asChild>
              <div className="space-y-3 pt-2 text-sm leading-relaxed">
                <p>
                  This incident is tied to an active anomaly that has not yet demonstrated
                  recovery against the learned telemetry baseline.
                </p>
                <p className="rounded-lg border border-amber-500/20 bg-amber-500/5 p-3 text-xs text-amber-800 dark:text-amber-200">
                  To prevent premature resolution and repeated incident alerts, FlowOps
                  requires consecutive healthy workflow executions before marking the incident
                  resolved.
                </p>
              </div>
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            {blockedIncident?.anomalyId && (
              <Button asChild variant="default">
                <Link
                  href={`/reliability/${blockedIncident.anomalyId}`}
                  onClick={() => setRecoveryBlockModalOpen(false)}
                >
                  Verify recovery on anomaly
                </Link>
              </Button>
            )}
            <Button
              variant="outline"
              onClick={() => setRecoveryBlockModalOpen(false)}
            >
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </PageContainer>
  );
}
