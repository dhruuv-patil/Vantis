"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  Bot,
  CheckCircle2,
  Clock,
  ExternalLink,
  Flame,
  Lightbulb,
  ListChecks,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { toast } from "sonner";
import { format, formatDistanceToNowStrict, isValid } from "date-fns";

import {
  acknowledgeIncident,
  fetchAnomaly,
  fetchIncident,
  getErrorMessage,
  resolveIncident,
} from "@/lib/api";

import type {
  AIInvestigationResult,
  AnomalyDetail,
  AnomalySeverity,
  Incident,
  IncidentStatus,
} from "@/types";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  ErrorState,
  PageContainer,
  TableSkeleton,
} from "@/components/app/page-primitives";

const SEVERITY_CONFIG: Record<
  AnomalySeverity,
  { label: string; dot: string; text: string; bg: string }
> = {
  LOW: {
    label: "Low",
    dot: "bg-green-500",
    text: "text-green-700 dark:text-green-300",
    bg: "bg-green-500/10 border-green-500/20",
  },
  MEDIUM: {
    label: "Medium",
    dot: "bg-yellow-500",
    text: "text-yellow-700 dark:text-yellow-300",
    bg: "bg-yellow-500/10 border-yellow-500/20",
  },
  HIGH: {
    label: "High",
    dot: "bg-orange-500",
    text: "text-orange-700 dark:text-orange-300",
    bg: "bg-orange-500/10 border-orange-500/20",
  },
  CRITICAL: {
    label: "Critical",
    dot: "bg-red-500",
    text: "text-red-700 dark:text-red-300",
    bg: "bg-red-500/10 border-red-500/20",
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

export default function IncidentDetailPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const incidentId = String(params.id ?? "");

  const [recoveryBlockModalOpen, setRecoveryBlockModalOpen] = useState(false);
  const [checkedItems, setCheckedItems] = useState<Record<number, boolean>>({});

  const incidentQuery = useQuery({
    queryKey: ["incident", incidentId],
    queryFn: () => fetchIncident(incidentId),
    enabled: Boolean(incidentId),
    refetchInterval: 10_000,
  });

  const incident = incidentQuery.data;

  // Also query the linked anomaly if present
  const anomalyQuery = useQuery({
    queryKey: ["anomaly", incident?.anomalyId],
    queryFn: () => fetchAnomaly(incident!.anomalyId!),
    enabled: Boolean(incident?.anomalyId),
  });

  const anomaly = anomalyQuery.data;

  const acknowledgeMutation = useMutation({
    mutationFn: () => acknowledgeIncident(incidentId),
    onSuccess: () => {
      toast.success("Incident acknowledged");
      queryClient.invalidateQueries({ queryKey: ["incident", incidentId] });
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
    onError: (err) => {
      toast.error(getErrorMessage(err) || "Failed to acknowledge incident");
    },
  });

  const resolveMutation = useMutation({
    mutationFn: () => resolveIncident(incidentId),
    onSuccess: () => {
      toast.success("Incident resolved");
      queryClient.invalidateQueries({ queryKey: ["incident", incidentId] });
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
    onError: (err: any) => {
      const msg = getErrorMessage(err);
      if (
        msg.includes("recovery is verified") ||
        msg.includes("ANOMALY_ALREADY_CLOSED") ||
        err?.response?.status === 409
      ) {
        setRecoveryBlockModalOpen(true);
      } else {
        toast.error(msg || "Failed to resolve incident");
      }
    },
  });

  const investigation: AIInvestigationResult | null = useMemo(() => {
    if (!anomaly?.evidence) return null;
    return (anomaly.evidence.investigation as AIInvestigationResult) || null;
  }, [anomaly]);

  if (incidentQuery.isPending) {
    return (
      <PageContainer>
        <div className="space-y-6">
          <div className="h-6 w-32 bg-muted animate-pulse rounded" />
          <div className="h-10 w-96 bg-muted animate-pulse rounded" />
          <TableSkeleton rows={3} cols={3} />
        </div>
      </PageContainer>
    );
  }

  if (incidentQuery.isError || !incident) {
    return (
      <PageContainer>
        <ErrorState
          title="Incident not found"
          message={getErrorMessage(incidentQuery.error) || "This incident could not be found or you do not have permission to view it."}
          onRetry={() => incidentQuery.refetch()}
        />
      </PageContainer>
    );
  }

  const sev = SEVERITY_CONFIG[incident.severity] ?? SEVERITY_CONFIG.MEDIUM;
  const stat = STATUS_CONFIG[incident.status] ?? STATUS_CONFIG.OPEN;

  const createdAt = isValid(new Date(incident.createdAt))
    ? format(new Date(incident.createdAt), "PPpp")
    : incident.createdAt;

  const timeAgo = isValid(new Date(incident.createdAt))
    ? formatDistanceToNowStrict(new Date(incident.createdAt), { addSuffix: true })
    : "";

  return (
    <PageContainer>
      <div className="space-y-6">
        {/* Navigation Breadcrumb */}
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Link
            href="/incidents"
            className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
          >
            <ArrowLeft className="size-3.5" />
            <span>Incidents</span>
          </Link>
          <span>/</span>
          <span className="font-mono">{incident.id.slice(0, 8)}</span>
        </div>

        {/* Header Strip */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between border-b border-border dark:border-white/[0.08] pb-6">
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2.5">
              <span
                className={`inline-flex items-center gap-1.5 rounded-md border px-2.5 py-0.5 text-xs font-semibold ${sev.bg} ${sev.text}`}
              >
                <span className={`size-2 rounded-full ${sev.dot}`} />
                {sev.label} Severity
              </span>

              <span
                className={`inline-flex items-center rounded-md border px-2.5 py-0.5 text-xs font-semibold ${stat.className}`}
              >
                {stat.label}
              </span>

              {incident.fingerprint && (
                <span className="rounded-md border border-border dark:border-white/[0.06] bg-muted/40 px-2 py-0.5 font-mono text-xs text-muted-foreground">
                  fp: {incident.fingerprint}
                </span>
              )}
            </div>

            <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-foreground dark:text-white/95">
              {incident.title}
            </h1>

            {incident.description && (
              <p className="text-sm text-muted-foreground max-w-3xl leading-relaxed">
                {incident.description}
              </p>
            )}

            <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground pt-1">
              <span className="inline-flex items-center gap-1">
                <Clock className="size-3.5" />
                Detected {createdAt} ({timeAgo})
              </span>
              {incident.acknowledgedAt && (
                <span>
                  Acknowledged: {format(new Date(incident.acknowledgedAt), "PPp")}
                </span>
              )}
              {incident.resolvedAt && (
                <span>
                  Resolved: {format(new Date(incident.resolvedAt), "PPp")}
                </span>
              )}
            </div>
          </div>

          {/* Action Buttons */}
          <div className="flex items-center gap-2 self-start">
            {incident.status === "OPEN" && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => acknowledgeMutation.mutate()}
                disabled={acknowledgeMutation.isPending}
              >
                Acknowledge
              </Button>
            )}

            {incident.status !== "RESOLVED" && (
              <Button
                variant="default"
                size="sm"
                onClick={() => resolveMutation.mutate()}
                disabled={resolveMutation.isPending}
              >
                Resolve Incident
              </Button>
            )}
          </div>
        </div>

        {/* Incident Context Grid */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <Card className="border-border dark:border-white/[0.08] bg-card">
            <CardHeader className="pb-2">
              <CardTitle className="text-xs font-medium text-muted-foreground">
                Affected Workflow
              </CardTitle>
            </CardHeader>
            <CardContent>
              {incident.workflowId ? (
                <div className="space-y-1">
                  <div className="text-sm font-semibold truncate">
                    {anomaly?.workflowName || "Workflow"}
                  </div>
                  <div className="font-mono text-xs text-muted-foreground truncate">
                    {incident.workflowId}
                  </div>
                </div>
              ) : (
                <span className="text-sm text-muted-foreground">Universal / Unspecified</span>
              )}
            </CardContent>
          </Card>

          <Card className="border-border dark:border-white/[0.08] bg-card">
            <CardHeader className="pb-2">
              <CardTitle className="text-xs font-medium text-muted-foreground">
                Triggering Execution
              </CardTitle>
            </CardHeader>
            <CardContent>
              {incident.executionId ? (
                <div className="space-y-1">
                  <Link
                    href={`/executions/${incident.executionId}`}
                    className="inline-flex items-center gap-1 text-sm font-semibold text-primary hover:underline truncate"
                  >
                    <span>View Execution</span>
                    <ExternalLink className="size-3.5" />
                  </Link>
                  <div className="font-mono text-xs text-muted-foreground truncate">
                    {incident.executionId}
                  </div>
                </div>
              ) : (
                <span className="text-sm text-muted-foreground">Schedule / Polling</span>
              )}
            </CardContent>
          </Card>

          <Card className="border-border dark:border-white/[0.08] bg-card">
            <CardHeader className="pb-2">
              <CardTitle className="text-xs font-medium text-muted-foreground">
                Triggering Anomaly
              </CardTitle>
            </CardHeader>
            <CardContent>
              {incident.anomalyId ? (
                <div className="space-y-1">
                  <Link
                    href={`/reliability/${incident.anomalyId}`}
                    className="inline-flex items-center gap-1 text-sm font-semibold text-primary hover:underline truncate"
                  >
                    <span>Inspect Anomaly Card</span>
                    <ExternalLink className="size-3.5" />
                  </Link>
                  <div className="text-xs text-muted-foreground">
                    Status: <Badge variant="outline" className="text-[10px] ml-1">{anomaly?.status || "OPEN"}</Badge>
                  </div>
                </div>
              ) : (
                <span className="text-sm text-muted-foreground">Manual / System trigger</span>
              )}
            </CardContent>
          </Card>
        </div>

        {/* Evidence & Baseline Timeline */}
        {anomaly && (
          <Card className="border-border dark:border-white/[0.08]">
            <CardHeader>
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <Flame className="size-4 text-orange-500" />
                <span>Measured Telemetry & Baseline Comparison</span>
              </CardTitle>
              <CardDescription className="text-xs">
                Statistical evidence captured when the anomaly detector triggered this incident.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 p-4 rounded-lg bg-muted/30 dark:bg-white/[0.02] border border-border dark:border-white/[0.06]">
                <div>
                  <div className="text-xs text-muted-foreground">Metric</div>
                  <div className="font-semibold text-sm mt-0.5">{anomaly.metric || "telemetry"}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Observed Actual</div>
                  <div className="font-semibold text-sm text-red-600 dark:text-red-400 mt-0.5">
                    {anomaly.actualValue || "Unavailable"}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Baseline Expected</div>
                  <div className="font-semibold text-sm text-emerald-600 dark:text-emerald-400 mt-0.5">
                    {anomaly.expectedValue || "Unavailable"}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Statistical Deviation</div>
                  <div className="font-semibold text-sm mt-0.5">
                    {anomaly.deviation > 0 ? `${anomaly.deviation.toFixed(1)}x MAD` : "Outlier"}
                  </div>
                </div>
              </div>

              {anomaly.nodeId && (
                <div className="text-xs text-muted-foreground">
                  Isolated Step: <span className="font-mono text-foreground dark:text-white/80">{anomaly.nodeId}</span>
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* AI Investigation Section */}
        {investigation && (
          <Card className="border-border dark:border-white/[0.08] shadow-sm">
            <CardHeader className="pb-3 border-b border-border dark:border-white/[0.06]">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <div className="flex size-7 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <Sparkles className="size-4" />
                  </div>
                  <div>
                    <CardTitle className="text-sm font-semibold">
                      Grounded AI Investigation
                    </CardTitle>
                    <CardDescription className="text-xs">
                      {investigation.configured
                        ? `Generated by ${investigation.model || "AI Service"}`
                        : "Deterministic Rule-based Diagnostic Fallback"}
                    </CardDescription>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  {investigation.fallback && (
                    <Badge variant="secondary" className="text-xs bg-amber-500/10 text-amber-600 border-amber-500/20">
                      Rule-based Fallback
                    </Badge>
                  )}
                  <Badge variant="outline" className="text-xs font-mono">
                    {Math.round(investigation.confidence * 100)}% confidence
                  </Badge>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-6 pt-5">
              {/* Summary */}
              {investigation.summary && (
                <div>
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
                    Diagnostic Summary
                  </h4>
                  <p className="text-sm leading-relaxed text-foreground/90 dark:text-white/80">
                    {investigation.summary}
                  </p>
                </div>
              )}

              {/* Measured Facts & Citations */}
              {investigation.evidenceCitations && investigation.evidenceCitations.length > 0 && (
                <div>
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                    Measured Facts & Cited Telemetry Fields
                  </h4>
                  <div className="flex flex-wrap gap-1.5">
                    {investigation.evidenceCitations.map((citation, idx) => (
                      <span
                        key={idx}
                        className="inline-flex items-center rounded-md border border-primary/20 bg-primary/[0.04] px-2 py-0.5 font-mono text-xs text-primary"
                      >
                        {citation}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Likely Causes */}
              {investigation.likelyCauses && investigation.likelyCauses.length > 0 && (
                <div>
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                    Hypotheses & Likely Causes
                  </h4>
                  <div className="space-y-3">
                    {investigation.likelyCauses.map((cause, idx) => (
                      <div
                        key={idx}
                        className="rounded-lg border border-border dark:border-white/[0.06] p-3.5 bg-muted/20 space-y-1.5"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-sm font-semibold text-foreground dark:text-white/90">
                            {cause.cause}
                          </span>
                          <span className="text-xs font-mono px-2 py-0.5 rounded bg-muted dark:bg-white/[0.04]">
                            {Math.round(cause.confidence * 100)}% confidence
                          </span>
                        </div>
                        {cause.uncertainty && (
                          <p className="text-xs text-muted-foreground">
                            <span className="font-medium text-foreground/70">Uncertainty:</span> {cause.uncertainty}
                          </p>
                        )}
                        {cause.citations && cause.citations.length > 0 && (
                          <div className="flex flex-wrap gap-1 pt-1">
                            {cause.citations.map((c, i) => (
                              <span
                                key={i}
                                className="font-mono text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20"
                              >
                                {c}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Suggested Fix */}
              {investigation.suggestedFix && (
                <div className="rounded-lg border border-primary/30 bg-primary/[0.03] p-4 space-y-1.5">
                  <div className="flex items-center gap-2 text-xs font-semibold text-primary uppercase tracking-wide">
                    <Lightbulb className="size-4" />
                    <span>Suggested Remediation</span>
                  </div>
                  <p className="text-sm font-medium text-foreground dark:text-white/90 leading-relaxed">
                    {investigation.suggestedFix}
                  </p>
                </div>
              )}

              {/* Verification Checklist */}
              {investigation.verificationChecklist && investigation.verificationChecklist.length > 0 && (
                <div className="space-y-3">
                  <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    <ListChecks className="size-4" />
                    <span>Recovery Verification Checklist</span>
                  </div>
                  <div className="space-y-2 rounded-lg border border-border dark:border-white/[0.06] p-4 bg-muted/10">
                    {investigation.verificationChecklist.map((step, idx) => (
                      <label
                        key={idx}
                        className="flex items-start gap-2.5 cursor-pointer text-xs leading-relaxed select-none hover:text-foreground"
                      >
                        <input
                          type="checkbox"
                          checked={Boolean(checkedItems[idx])}
                          onChange={(e) =>
                            setCheckedItems((prev) => ({
                              ...prev,
                              [idx]: e.target.checked,
                            }))
                          }
                          className="mt-0.5 rounded border-border text-primary focus:ring-primary size-3.5"
                        />
                        <span className={checkedItems[idx] ? "line-through text-muted-foreground" : "text-foreground dark:text-white/80"}>
                          {step}
                        </span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>

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
            <DialogDescription className="space-y-3 pt-2 text-sm leading-relaxed">
              <p>
                This incident is linked to an anomaly that has not yet demonstrated recovery
                against the established baseline.
              </p>
              <p className="rounded-lg border border-amber-500/20 bg-amber-500/5 p-3 text-xs text-amber-800 dark:text-amber-200">
                To prevent premature resolution, FlowOps requires healthy consecutive executions
                before an incident can be closed.
              </p>
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            {incident.anomalyId && (
              <Button asChild variant="default">
                <Link
                  href={`/reliability/${incident.anomalyId}`}
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
