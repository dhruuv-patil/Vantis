"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  MoreHorizontal,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";
import { toast } from "sonner";
import { formatDistanceToNowStrict, isValid } from "date-fns";

import {
  acknowledgeIncident,
  fetchIncidents,
  getErrorMessage,
  resolveIncident,
} from "@/lib/api";

import type { Incident, IncidentStatus, AnomalySeverity } from "@/types";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  EmptyState,
  ErrorState,
  KpiStrip,
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
    dot: "bg-green-500",
    text: "text-muted-foreground dark:text-white/60",
  },
  MEDIUM: {
    label: "Medium",
    dot: "bg-yellow-500",
    text: "text-foreground/80 dark:text-white/70",
  },
  HIGH: {
    label: "High",
    dot: "bg-orange-500",
    text: "text-foreground dark:text-white/80",
  },
  CRITICAL: {
    label: "Critical",
    dot: "bg-red-500",
    text: "text-red-600 dark:text-red-300",
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

export default function IncidentsPage() {
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(ALL);
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>(ALL);
  const [recoveryBlockModalOpen, setRecoveryBlockModalOpen] = useState(false);
  const [blockedIncident, setBlockedIncident] = useState<Incident | null>(null);

  const incidentsQuery = useQuery({
    queryKey: ["incidents"],
    queryFn: () => fetchIncidents(),
    refetchInterval: 15_000,
  });

  const acknowledgeMutation = useMutation({
    mutationFn: (id: string) => acknowledgeIncident(id),
    onSuccess: (updated) => {
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

  const kpis = useMemo(() => {
    const open = incidents.filter((i) => i.status === "OPEN");
    const criticalOrHigh = open.filter(
      (i) => i.severity === "CRITICAL" || i.severity === "HIGH",
    );
    const acknowledged = incidents.filter((i) => i.status === "ACKNOWLEDGED");
    const resolved = incidents.filter((i) => i.status === "RESOLVED");

    return [
      {
        label: "Active incidents",
        value: open.length,
        description: "Unresolved issues across workflows",
      },
      {
        label: "Critical / High",
        value: criticalOrHigh.length,
        description: "Require immediate operator attention",
      },
      {
        label: "Acknowledged",
        value: acknowledged.length,
        description: "Under active investigation",
      },
      {
        label: "Resolved",
        value: resolved.length,
        description: "Verified healthy by baseline telemetry",
      },
    ];
  }, [incidents]);

  const filteredIncidents = useMemo(() => {
    return incidents.filter((inc) => {
      if (statusFilter !== ALL && inc.status !== statusFilter) return false;
      if (severityFilter !== ALL && inc.severity !== severityFilter) return false;
      if (search.trim()) {
        const q = search.toLowerCase();
        const matchTitle = inc.title.toLowerCase().includes(q);
        const matchDesc = (inc.description ?? "").toLowerCase().includes(q);
        const matchFingerprint = (inc.fingerprint ?? "").toLowerCase().includes(q);
        if (!matchTitle && !matchDesc && !matchFingerprint) return false;
      }
      return true;
    });
  }, [incidents, statusFilter, severityFilter, search]);

  return (
    <PageContainer>
      <PageHeader
        title="Incidents"
        description="Unified reliability incident tracking for automated workflows. Incidents require baseline recovery verification before closing."
      />

      <KpiStrip items={kpis} />

      <Toolbar className="mb-4">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground dark:text-white/40" />
          <Input
            placeholder="Filter incidents by title, description or fingerprint..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-10 text-sm"
          />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as StatusFilter)}
          >
            <SelectTrigger className="w-[140px] h-9 text-xs">
              <SelectValue placeholder="Status" />
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
            onValueChange={(v) => setSeverityFilter(v as SeverityFilter)}
          >
            <SelectTrigger className="w-[140px] h-9 text-xs">
              <SelectValue placeholder="Severity" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>All severities</SelectItem>
              <SelectItem value="CRITICAL">Critical</SelectItem>
              <SelectItem value="HIGH">High</SelectItem>
              <SelectItem value="MEDIUM">Medium</SelectItem>
              <SelectItem value="LOW">Low</SelectItem>
            </SelectContent>
          </Select>

          <Button
            variant="outline"
            size="sm"
            className="h-9 px-2.5 text-xs gap-1.5"
            onClick={() => queryClient.invalidateQueries({ queryKey: ["incidents"] })}
          >
            <RefreshCw className="size-3.5" />
            Refresh
          </Button>
        </div>
      </Toolbar>

      {incidentsQuery.isPending ? (
        <TableSkeleton rows={5} cols={6} />
      ) : incidentsQuery.isError ? (
        <ErrorState
          title="Could not load incidents"
          message={getErrorMessage(incidentsQuery.error)}
          onRetry={() => incidentsQuery.refetch()}
        />
      ) : filteredIncidents.length === 0 ? (
        <EmptyState
          icon={ShieldCheck}
          title="No incidents found"
          description={
            search || statusFilter !== ALL || severityFilter !== ALL
              ? "No incidents matched your selected filter criteria."
              : "All workflows are currently performing reliably within expected baselines."
          }
        />
      ) : (
        <div className="rounded-xl border border-border dark:border-white/[0.08] bg-card overflow-hidden">
          <Table>
            <TableHeader className="bg-muted/40 dark:bg-white/[0.02]">
              <TableRow>
                <TableHead className="w-[340px]">Incident</TableHead>
                <TableHead className="w-[110px]">Severity</TableHead>
                <TableHead className="w-[120px]">Status</TableHead>
                <TableHead>Workflow / Anomaly</TableHead>
                <TableHead className="w-[140px]">Detected</TableHead>
                <TableHead className="w-[80px] text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredIncidents.map((incident) => {
                const sev = SEVERITY_CONFIG[incident.severity] ?? SEVERITY_CONFIG.MEDIUM;
                const stat = STATUS_CONFIG[incident.status] ?? STATUS_CONFIG.OPEN;

                const detectedDate = new Date(incident.createdAt);
                const timeAgo = isValid(detectedDate)
                  ? formatDistanceToNowStrict(detectedDate, { addSuffix: true })
                  : "recently";

                return (
                  <TableRow key={incident.id} className="group hover:bg-muted/30">
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
                      <div className="flex items-center gap-2">
                        <span className={`size-2 rounded-full ${sev.dot}`} />
                        <span className={`text-xs font-medium ${sev.text}`}>
                          {sev.label}
                        </span>
                      </div>
                    </TableCell>

                    <TableCell>
                      <span
                        className={`inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium ${stat.className}`}
                      >
                        {stat.label}
                      </span>
                    </TableCell>

                    <TableCell>
                      <div className="flex flex-col gap-0.5 text-xs text-muted-foreground">
                        {incident.workflowId && (
                          <span className="font-mono text-[11px]">
                            wf: {incident.workflowId.slice(0, 8)}...
                          </span>
                        )}
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

                    <TableCell className="text-xs text-muted-foreground">
                      {timeAgo}
                    </TableCell>

                    <TableCell className="text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="size-8">
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
                              <Link href={`/reliability/${incident.anomalyId}`}>
                                View linked anomaly
                              </Link>
                            </DropdownMenuItem>
                          )}
                          <DropdownMenuSeparator />
                          {incident.status === "OPEN" && (
                            <DropdownMenuItem
                              onSelect={() => acknowledgeMutation.mutate(incident.id)}
                              onClick={() => acknowledgeMutation.mutate(incident.id)}
                            >
                              Acknowledge
                            </DropdownMenuItem>
                          )}
                          {incident.status !== "RESOLVED" && (
                            <DropdownMenuItem
                              onSelect={() => resolveMutation.mutate(incident.id)}
                              onClick={() => resolveMutation.mutate(incident.id)}
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
