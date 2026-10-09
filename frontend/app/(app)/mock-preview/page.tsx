"use client";

import { useState } from "react";
import Link from "next/link";
import {
  Activity,
  AlertCircle,
  ArrowUpRight,
  Bot,
  ExternalLink,
  FileText,
  Plug,
  RefreshCw,
  Search,
  ShieldCheck,
  UserCheck,
  UserPlus,
  Workflow,
  X,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  CardGridSkeleton,
  CategoryPillsRow,
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { cn } from "@/lib/utils";

type RouteKey =
  | "workflows"
  | "executions"
  | "anomalies"
  | "logs"
  | "agents"
  | "integrations"
  | "team"
  | "templates";

type StateKey =
  | "empty"
  | "single"
  | "high_volume"
  | "long_text"
  | "loading"
  | "error"
  | "filtered_zero";

const ROUTES: { label: string; value: RouteKey }[] = [
  { label: "Workflows", value: "workflows" },
  { label: "Executions", value: "executions" },
  { label: "Anomalies", value: "anomalies" },
  { label: "Logs", value: "logs" },
  { label: "Agents", value: "agents" },
  { label: "Integrations", value: "integrations" },
  { label: "Team", value: "team" },
  { label: "Templates", value: "templates" },
];

const STATES: { label: string; value: StateKey }[] = [
  { label: "Empty (0)", value: "empty" },
  { label: "Single (1)", value: "single" },
  { label: "50+ Items", value: "high_volume" },
  { label: "Long Text / Edge", value: "long_text" },
  { label: "Loading Skeleton", value: "loading" },
  { label: "Backend Error", value: "error" },
  { label: "Filtered to Zero", value: "filtered_zero" },
];

export default function MockPreviewPage() {
  const [activeRoute, setActiveRoute] = useState<RouteKey>("workflows");
  const [activeState, setActiveState] = useState<StateKey>("high_volume");

  return (
    <PageContainer variant="wide">
      <PageHeader
        eyebrow="Developer Preview"
        title="Edge States & Mock Preview"
        description="Verify edge cases, loading skeletons, error states, long text truncation, and 50+ item pagination across all ObLayer list routes without backend data."
      />

      <div className="mb-6 space-y-4 rounded-xl border border-border bg-card p-4 dark:border-white/[0.08] dark:bg-[#0c0c0e]">
        <div>
          <span className="text-xs font-medium text-muted-foreground dark:text-white/50">Select Surface</span>
          <div className="mt-2">
            <CategoryPillsRow
              categories={ROUTES}
              activeValue={activeRoute}
              onSelect={(val) => setActiveRoute(val as RouteKey)}
              ariaLabel="Select surface to test"
            />
          </div>
        </div>

        <div className="border-t border-border pt-3 dark:border-white/[0.06]">
          <span className="text-xs font-medium text-muted-foreground dark:text-white/50">Select State</span>
          <div className="mt-2">
            <CategoryPillsRow
              categories={STATES}
              activeValue={activeState}
              onSelect={(val) => setActiveState(val as StateKey)}
              ariaLabel="Select state scenario"
            />
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card/60 p-6 dark:border-white/[0.08] dark:bg-black/40">
        <div className="mb-4 flex items-center justify-between border-b border-border pb-3 dark:border-white/[0.06]">
          <span className="font-mono text-xs text-muted-foreground dark:text-white/40">
            Previewing: <strong className="text-foreground dark:text-white/80">{activeRoute}</strong> · State:{" "}
            <strong className="text-foreground dark:text-white/80">{activeState}</strong>
          </span>
          <span className="rounded bg-muted/40 px-2 py-0.5 font-mono text-[10px] text-muted-foreground dark:bg-white/[0.05] dark:text-white/50">
            Dev Mock Harness
          </span>
        </div>

        {activeRoute === "workflows" && <WorkflowsMockPreview state={activeState} />}
        {activeRoute === "executions" && <ExecutionsMockPreview state={activeState} />}
        {activeRoute === "anomalies" && <AnomaliesMockPreview state={activeState} />}
        {activeRoute === "logs" && <LogsMockPreview state={activeState} />}
        {activeRoute === "agents" && <AgentsMockPreview state={activeState} />}
        {activeRoute === "integrations" && <IntegrationsMockPreview state={activeState} />}
        {activeRoute === "team" && <TeamMockPreview state={activeState} />}
        {activeRoute === "templates" && <TemplatesMockPreview state={activeState} />}
      </div>
    </PageContainer>
  );
}

/* -------------------------------------------------------------------------- */
/* Workflows Mock Preview                                                     */
/* -------------------------------------------------------------------------- */

function WorkflowsMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(24);

  if (state === "loading") {
    return <CardGridSkeleton count={6} />;
  }

  if (state === "error") {
    return (
      <ErrorState
        title="Unable to load workflows"
        message="Simulated 500 internal server error from downstream cluster."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }

  if (state === "empty") {
    return (
      <EmptyState
        icon={Workflow}
        title="No workflows yet"
        description="Create your first workflow or start from a proven template."
        actions={
          <Button size="sm" variant="outline">
            Browse templates
          </Button>
        }
      />
    );
  }

  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={Workflow}
        title="No workflows found"
        description="Try changing your search or status filter."
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            id: "wf-1",
            name: "Customer Onboarding Webhook Pipeline",
            description: "Syncs new signups from Stripe to Postgres and notifies Slack channel.",
            status: "PUBLISHED",
            updatedAt: "2026-10-03T18:00:00Z",
          },
        ]
      : state === "long_text"
        ? [
            {
              id: "wf-long-1",
              name: "Supercalifragilisticexpialidocious Ultra Long Enterprise Workflow Name With Very Deep Nested Namespace Details That Might Break Grid Cells If Not Properly Truncated",
              description:
                "This is an extraordinarily verbose description designed to rigorously verify multi-line line clamping and tooltip presentation. It contains long unbroken tokens such as https://internal.vantis.run/api/v1/workspaces/ws_9876543210/pipelines/execute/async-with-heavy-payload?debug=true&telemetry=enabled and ensures that cards maintain identical heights without overflowing parent boundaries.",
              status: "PUBLISHED",
              updatedAt: "2026-10-03T18:00:00Z",
            },
            {
              id: "wf-long-2",
              name: "Another_Unbroken_Snake_Case_Identifier_Name_For_Testing_Layout_Integrity_Without_Spaces_1234567890",
              description: "Short description.",
              status: "DRAFT",
              updatedAt: "2026-10-03T17:00:00Z",
            },
          ]
        : Array.from({ length: 60 }).map((_, i) => ({
            id: `wf-${i + 1}`,
            name: `Production Workflow ${i + 1} · Service Pipeline`,
            description: `Automated data sync and validation routine running for shard cluster ${i + 1}.`,
            status: i % 3 === 0 ? "DRAFT" : "PUBLISHED",
            updatedAt: new Date(Date.now() - i * 3600000).toISOString(),
          }));

  return (
    <div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {items.slice(0, pageSize).map((wf) => (
          <article
            key={wf.id}
            className="flex min-h-[176px] flex-col overflow-hidden rounded-xl border border-border bg-card p-5 dark:border-white/[0.065] dark:bg-[#0a0a0c]"
          >
            <div className="flex h-7 items-center justify-between">
              <StatusBadge status={wf.status} />
              <span className="font-mono text-[10px] text-muted-foreground dark:text-white/35">{wf.id}</span>
            </div>
            <h3
              title={wf.name}
              className="mt-3 truncate text-base font-medium tracking-tight text-foreground/90 dark:text-white/90"
            >
              {wf.name}
            </h3>
            <p
              title={wf.description}
              className="mt-1.5 line-clamp-2 text-xs leading-relaxed text-muted-foreground dark:text-white/60"
            >
              {wf.description}
            </p>
            <div className="mt-auto pt-4 text-[11px] text-muted-foreground dark:text-white/40">
              Updated {new Date(wf.updatedAt).toLocaleTimeString()}
            </div>
          </article>
        ))}
      </div>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 24)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Executions Mock Preview                                                    */
/* -------------------------------------------------------------------------- */

function ExecutionsMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(25);

  if (state === "loading") return <TableSkeleton rows={8} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Unable to load executions"
        message="Execution telemetry gateway timeout."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={Activity}
        title="No executions yet"
        description="Run a workflow or connect an external provider to see executions."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={Activity}
        title="No matching executions"
        description="Try a different source, status, or search term."
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            id: "exec-101",
            workflowName: "Hourly Stripe Reconciliation",
            status: "SUCCEEDED",
            duration: "1.4s",
            started: "5m ago",
            error: null,
          },
        ]
      : state === "long_text"
        ? [
            {
              id: "exec-long-12345678901234567890",
              workflowName:
                "Ultra Super Hyper Mega Long Workflow Pipeline Name With Multiple Environments And Sharded Database Sync Identifiers",
              status: "FAILED",
              duration: "45.2s",
              started: "Just now",
              error:
                "FatalException: Connection to cluster postgres-primary.us-east-1.internal:5432 timed out after 30000ms with query retry budget exhausted",
            },
          ]
        : Array.from({ length: 75 }).map((_, i) => ({
            id: `exec-${1000 + i}`,
            workflowName: `Service Pipeline Run #${i + 1}`,
            status: i % 5 === 0 ? "FAILED" : i % 7 === 0 ? "RUNNING" : "SUCCEEDED",
            duration: `${(1 + (i % 8) * 0.4).toFixed(1)}s`,
            started: `${i + 1}m ago`,
            error: i % 5 === 0 ? "Timeout in webhook node 3" : null,
          }));

  return (
    <div>
      <div className="overflow-hidden rounded-xl border border-border bg-card dark:border-white/[0.065] dark:bg-[#0a0a0c]">
        <div className="sticky top-0 z-10 hidden h-10 items-center border-b border-border bg-muted/40 px-4 font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground backdrop-blur-sm dark:border-white/[0.05] dark:bg-[#0c0c0e]/95 dark:text-white/40 md:flex">
          <div className="w-[120px]">Status</div>
          <div className="flex-1">Workflow</div>
          <div className="w-[110px] text-right">Duration</div>
          <div className="w-[105px] text-right">Started</div>
        </div>

        <ul className="divide-y divide-border dark:divide-white/[0.05]">
          {items.slice(0, pageSize).map((item) => (
            <li key={item.id} className="flex items-center gap-3 px-4 py-3.5 hover:bg-muted/40 dark:hover:bg-white/[0.025]">
              <div className="w-[110px] shrink-0">
                <StatusBadge status={item.status} />
              </div>
              <div className="min-w-0 flex-1">
                <span
                  title={item.workflowName}
                  className="block truncate text-sm font-medium text-foreground/85 dark:text-white/85"
                >
                  {item.workflowName}
                </span>
                <span
                  title={item.error ?? undefined}
                  className="block truncate text-xs text-muted-foreground dark:text-white/50"
                >
                  id: {item.id} {item.error ? `· ${item.error}` : ""}
                </span>
              </div>
              <span className="w-[110px] shrink-0 text-right font-mono text-xs text-muted-foreground dark:text-white/45">
                {item.duration}
              </span>
              <span className="w-[105px] shrink-0 text-right font-mono text-xs text-muted-foreground dark:text-white/40">
                {item.started}
              </span>
            </li>
          ))}
        </ul>
      </div>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 25)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Anomalies Mock Preview                                                     */
/* -------------------------------------------------------------------------- */

function AnomaliesMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(25);

  if (state === "loading") return <TableSkeleton rows={6} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Unable to load anomalies"
        message="Database replica lag exceeded detection tolerance threshold."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="No anomalies detected"
        description="Detection starts once your workflows have enough run history to establish a baseline."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="No anomalies match your filters"
        description="Try changing your status filter or search term."
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            id: "anom-1",
            workflowName: "User Profile Sync Pipeline",
            nodeId: "http_post_webhook",
            type: "Latency spike",
            metric: "p99 duration > 5000ms",
            severity: "HIGH",
            status: "OPEN",
            detected: "12m ago",
          },
        ]
      : state === "long_text"
        ? [
            {
              id: "anom-long-1",
              workflowName:
                "Ultra Long Enterprise Multi-Region Cross-Cluster Ingestion Pipeline With High Volume Re-Routing",
              nodeId: "very_long_custom_node_identifier_that_spans_multiple_characters_xyz123",
              type: "Error rate surge following vendor API outage",
              metric: "Failure rate rose from 0.02% to 48.7% over 15 minutes",
              severity: "CRITICAL",
              status: "OPEN",
              detected: "2m ago",
            },
          ]
        : Array.from({ length: 65 }).map((_, i) => ({
            id: `anom-${i + 1}`,
            workflowName: `Workflow Pipeline ${i + 1}`,
            nodeId: `node_${i + 1}`,
            type: i % 2 === 0 ? "Latency spike" : "Error burst",
            metric: `${100 + i * 20}ms above baseline`,
            severity: i % 4 === 0 ? "CRITICAL" : i % 2 === 0 ? "HIGH" : "MEDIUM",
            status: i % 3 === 0 ? "RESOLVED" : "OPEN",
            detected: `${i + 2}m ago`,
          }));

  return (
    <div>
      <div className="overflow-hidden rounded-xl border border-border bg-card dark:border-white/[0.065] dark:bg-[#0a0a0c]">
        <Table>
          <TableHeader className="sticky top-0 z-10 bg-muted/40 backdrop-blur-sm dark:bg-[#0c0c0e]/95">
            <TableRow>
              <TableHead>Workflow</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Severity</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Detected</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.slice(0, pageSize).map((item) => (
              <TableRow key={item.id}>
                <TableCell>
                  <span
                    title={item.workflowName}
                    className="block max-w-[200px] truncate font-medium text-foreground/90 dark:text-white/90 sm:max-w-xs"
                  >
                    {item.workflowName}
                  </span>
                  <span
                    title={item.nodeId}
                    className="mt-0.5 block truncate font-mono text-xs text-muted-foreground dark:text-white/44"
                  >
                    {item.nodeId}
                  </span>
                </TableCell>
                <TableCell>
                  <span className="text-sm text-foreground/70 dark:text-white/70">{item.type}</span>
                  <span
                    title={item.metric}
                    className="mt-0.5 block max-w-[180px] truncate text-xs text-muted-foreground dark:text-white/44"
                  >
                    {item.metric}
                  </span>
                </TableCell>
                <TableCell>
                  <Badge variant={item.severity === "CRITICAL" ? "destructive" : "outline"}>
                    {item.severity}
                  </Badge>
                </TableCell>
                <TableCell>
                  <StatusBadge status={item.status} />
                </TableCell>
                <TableCell className="font-mono text-xs text-muted-foreground dark:text-white/44">{item.detected}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 25)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Logs Mock Preview                                                          */
/* -------------------------------------------------------------------------- */

function LogsMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(50);

  if (state === "loading") return <TableSkeleton rows={6} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Could not load logs"
        message="Log ingestion stream disconnected."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={FileText}
        title="No run logs yet"
        description="Run a published workflow and its log lines will appear here."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={FileText}
        title='No logs match "query"'
        description="Try adjusting your search query or log level filter."
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            id: "log-1",
            level: "INFO",
            message: "Dispatched payload to https://api.stripe.com/v1/refunds successfully.",
            workflow: "Billing Pipeline",
            time: "1m ago",
          },
        ]
      : state === "long_text"
        ? [
            {
              id: "log-long-1",
              level: "ERROR",
              message:
                'JSON.parse error: Unexpected token < in JSON at position 0: "<html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center><hr><center>cloudflare-nginx</center></body></html>" with additional trace context {"span_id":"0123456789abcdef","trace_id":"abcdef0123456789abcdef0123456789"}',
              workflow:
                "Ultra Long Workflow Pipeline Name With Multiple Steps And Highly Specific Tags",
              time: "Just now",
            },
          ]
        : Array.from({ length: 80 }).map((_, i) => ({
            id: `log-${i + 1}`,
            level: i % 5 === 0 ? "ERROR" : i % 4 === 0 ? "WARN" : "INFO",
            message: `Executed node transform_item_${i + 1} with status code 200 OK.`,
            workflow: `Workflow #${(i % 10) + 1}`,
            time: `${i + 1}s ago`,
          }));

  return (
    <div>
      <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card dark:divide-white/[0.05] dark:border-white/[0.065] dark:bg-[#0a0a0c]">
        {items.slice(0, pageSize).map((log) => (
          <li key={log.id} className="flex items-start gap-3 px-4 py-3 hover:bg-muted/40 dark:hover:bg-white/[0.02]">
            <span
              className={cn(
                "w-14 shrink-0 pt-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider",
                log.level === "ERROR"
                  ? "text-red-500 dark:text-red-400"
                  : log.level === "WARN"
                    ? "text-amber-500 dark:text-amber-400"
                    : "text-foreground/85 dark:text-white/85",
              )}
            >
              {log.level}
            </span>
            <div className="min-w-0 flex-1">
              <span
                title={log.message}
                className="block break-words font-mono text-xs leading-relaxed text-foreground/80 dark:text-white/80"
              >
                {log.message}
              </span>
              <span title={log.workflow} className="mt-1 block truncate text-xs text-muted-foreground dark:text-white/40">
                {log.workflow}
              </span>
            </div>
            <span className="shrink-0 font-mono text-xs text-muted-foreground dark:text-white/40">{log.time}</span>
          </li>
        ))}
      </ul>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 50)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Agents Mock Preview                                                        */
/* -------------------------------------------------------------------------- */

function AgentsMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(25);

  if (state === "loading") return <TableSkeleton rows={4} cols={3} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Could not load agents"
        message="Failed to fetch AI agent configurations from registry."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={Bot}
        title="No AI agents yet"
        description="Create an agent with custom instructions and tools to automate steps in your workflows."
        actions={
          <Button size="sm" variant="outline">
            Browse workflows
          </Button>
        }
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={Bot}
        title="No agents match your search"
        description="Try adjusting your search terms to find what you're looking for."
        actions={
          <Button size="sm" variant="outline">
            Clear search
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            id: "agent-1",
            name: "Customer Support Triager",
            description: "Categorizes incoming Zendesk tickets and routes to correct tier.",
            model: "gpt-4o",
            tools: 3,
          },
        ]
      : state === "long_text"
        ? [
            {
              id: "agent-long-1",
              name: "Ultra Specialized Autonomous Multimodal Incident Mitigation And Diagnostic Assistant Agent",
              description:
                "Monitors Datadog alarms, analyzes trace spans, correlates database slow query logs, formulates root cause hypotheses, and posts recommendations to incident war rooms with full context.",
              model: "claude-3-5-sonnet-20241022",
              tools: 12,
            },
          ]
        : Array.from({ length: 55 }).map((_, i) => ({
            id: `agent-${i + 1}`,
            name: `AI Automation Agent ${i + 1}`,
            description: `Automates task processing routine #${i + 1} across connected systems.`,
            model: i % 2 === 0 ? "gpt-4o" : "claude-3-5-sonnet",
            tools: (i % 5) + 1,
          }));

  return (
    <div>
      <div className="overflow-hidden rounded-xl border border-border bg-card dark:border-white/[0.065] dark:bg-[#0a0a0c]">
        <ul className="divide-y divide-border dark:divide-white/[0.05]">
          {items.slice(0, pageSize).map((a) => (
            <li key={a.id} className="flex items-center gap-4 px-4 py-3.5 hover:bg-muted/40 dark:hover:bg-white/[0.04]">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/30 text-muted-foreground dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/60">
                <Bot className="size-4" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span title={a.name} className="truncate font-medium text-foreground/90 dark:text-white/90">
                    {a.name}
                  </span>
                  <Badge variant="outline" className="font-mono text-[10px] text-muted-foreground dark:text-white/50">
                    {a.model}
                  </Badge>
                </div>
                <span title={a.description} className="block truncate text-xs text-muted-foreground dark:text-white/45">
                  {a.description}
                </span>
              </div>
              <span className="shrink-0 text-xs text-muted-foreground dark:text-white/40">{a.tools} tools</span>
            </li>
          ))}
        </ul>
      </div>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 25)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Integrations Mock Preview                                                  */
/* -------------------------------------------------------------------------- */

function IntegrationsMockPreview({ state }: { state: StateKey }) {
  if (state === "loading") return <CardGridSkeleton count={6} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Could not load integrations"
        message="OAuth connector discovery registry unavailable."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={Plug}
        title="No integrations available"
        description="No third party integrations are configured for this workspace."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={Search}
        title="No integrations found"
        description='Nothing matches "search_query".'
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            name: "GitHub",
            desc: "Trigger workflows on push, PR, issue and release events.",
            connected: true,
          },
        ]
      : state === "long_text"
        ? [
            {
              name: "Ultra Custom Enterprise On-Premises Customer Relationship Management Service Integration",
              desc: "Provides bidirectional synchronization with on-premises data warehouses, enterprise billing mainframes, and proprietary legacy notification backends across secure private VPC links.",
              connected: false,
            },
          ]
        : Array.from({ length: 30 }).map((_, i) => ({
            name: `Provider Service ${i + 1}`,
            desc: `Connect your workflows to Provider Service #${i + 1} with OAuth credentials.`,
            connected: i < 3,
          }));

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {items.map((p, idx) => (
        <div
          key={idx}
          className="flex min-h-[220px] flex-col rounded-xl border border-border bg-card p-5 dark:border-white/[0.08] dark:bg-[#0a0a0c]"
        >
          <div className="flex items-center justify-between">
            <div className="flex size-10 items-center justify-center rounded-lg border border-border bg-muted/40 text-sm font-bold text-foreground/80 dark:border-white/[0.08] dark:bg-white/[0.03] dark:text-white/80">
              {p.name.slice(0, 2).toUpperCase()}
            </div>
            {p.connected ? (
              <span className="rounded bg-emerald-500/10 px-2 py-0.5 text-[10px] font-medium text-emerald-600 dark:text-emerald-300">
                Connected
              </span>
            ) : (
              <span className="rounded bg-muted/40 px-2 py-0.5 text-[10px] font-medium text-muted-foreground dark:bg-white/[0.04] dark:text-white/45">
                Available
              </span>
            )}
          </div>
          <h3 title={p.name} className="mt-4 truncate text-sm font-semibold text-foreground/90 dark:text-white/90">
            {p.name}
          </h3>
          <p title={p.desc} className="mt-1.5 line-clamp-2 text-xs text-muted-foreground dark:text-white/50">
            {p.desc}
          </p>
          <div className="mt-auto pt-4">
            <Button
              variant="outline"
              size="sm"
              className="h-9 w-full justify-between text-xs text-foreground/80 dark:text-white/80"
            >
              <span>{p.connected ? "Manage" : "Connect"}</span>
              <ArrowUpRight className="size-3.5 text-muted-foreground dark:text-white/40" />
            </Button>
          </div>
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Team Mock Preview                                                          */
/* -------------------------------------------------------------------------- */

function TeamMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(25);

  if (state === "loading") return <TableSkeleton rows={3} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Could not load members"
        message="Failed to fetch organization member roster."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={UserPlus}
        title="No members in organization"
        description="Invite your team to collaborate on workflows."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={UserCheck}
        title="No members match search"
        description="Check the spelling or clear the filter."
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            name: "Dhruv Patil",
            email: "dhruv@vantis.run",
            role: "OWNER",
          },
        ]
      : state === "long_text"
        ? [
            {
              name: "Hubert Blaine Wolfeschlegelsteinhausenbergerdorff Senior The Third",
              email:
                "hubert.blaine.wolfeschlegelsteinhausenbergerdorff.senior.third@ultra-enterprise-organization-domain-name.corp.internal",
              role: "ADMIN",
            },
          ]
        : Array.from({ length: 52 }).map((_, i) => ({
            name: `Team Member ${i + 1}`,
            email: `member.${i + 1}@organization.com`,
            role: i === 0 ? "OWNER" : i % 3 === 0 ? "ADMIN" : "MEMBER",
          }));

  return (
    <div>
      <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card dark:divide-white/[0.05] dark:border-white/[0.065] dark:bg-[#0a0a0c]">
        {items.slice(0, pageSize).map((m, idx) => (
          <li key={idx} className="flex items-center gap-3 px-4 py-3.5 hover:bg-muted/40 dark:hover:bg-white/[0.02]">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted/50 text-xs font-semibold text-foreground/80 dark:bg-white/[0.06] dark:text-white/80">
              {m.name.slice(0, 2).toUpperCase()}
            </span>
            <div className="min-w-0 flex-1">
              <p title={m.name} className="truncate text-sm font-medium text-foreground/90 dark:text-white/90">
                {m.name}
              </p>
              <p title={m.email} className="truncate text-xs text-muted-foreground dark:text-white/40">
                {m.email}
              </p>
            </div>
            <Badge variant={m.role === "OWNER" ? "default" : "secondary"}>{m.role}</Badge>
          </li>
        ))}
      </ul>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 25)}
      />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Templates Mock Preview                                                     */
/* -------------------------------------------------------------------------- */

function TemplatesMockPreview({ state }: { state: StateKey }) {
  const [pageSize, setPageSize] = useState(24);

  if (state === "loading") return <CardGridSkeleton count={6} />;
  if (state === "error") {
    return (
      <ErrorState
        title="Could not load templates"
        message="Template repository unreachable."
        onRetry={() => alert("Retry clicked")}
      />
    );
  }
  if (state === "empty") {
    return (
      <EmptyState
        icon={Workflow}
        title="No templates available"
        description="The template repository is currently empty."
      />
    );
  }
  if (state === "filtered_zero") {
    return (
      <EmptyState
        icon={Search}
        title="No templates found"
        description='Nothing matches "search_term".'
        actions={
          <Button size="sm" variant="outline">
            Clear filters
          </Button>
        }
      />
    );
  }

  const items =
    state === "single"
      ? [
          {
            name: "GitHub PR to Slack Alert",
            desc: "Sends clean interactive notifications on pull request reviews.",
            category: "Developer",
            tags: ["GitHub", "Slack"],
          },
        ]
      : state === "long_text"
        ? [
            {
              name: "Ultra Comprehensive End-To-End Cross-Platform Continuous Deployment Verification And Anomaly Rollback Template",
              desc: "Monitors canary deployment metrics in real time across Datadog, CloudWatch, and Prometheus. Automatically initiates progressive rollback, invalidates edge CDN caches, notifies on-call teams, and records a postmortem template draft.",
              category: "Observability & SRE Operations",
              tags: ["Automated Rollback", "Multi-Cloud", "PostgreSQL", "Kafka"],
            },
          ]
        : Array.from({ length: 50 }).map((_, i) => ({
            name: `Template Pattern #${i + 1}`,
            desc: `Preconfigured automation workflow template for production pattern #${i + 1}.`,
            category: i % 2 === 0 ? "Developer" : "Productivity",
            tags: [`Tool ${i + 1}`, "Webhook"],
          }));

  return (
    <div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {items.slice(0, pageSize).map((t, idx) => (
          <article
            key={idx}
            className="flex min-h-[220px] flex-col rounded-lg border border-border bg-card p-4 dark:border-white/[0.08] dark:bg-[#0a0a0a]"
          >
            <h3
              title={t.name}
              className="line-clamp-2 text-[15px] font-semibold leading-5 text-foreground/90 dark:text-white/90"
            >
              {t.name}
            </h3>
            <p className="mt-1 text-[11px] uppercase tracking-wider text-muted-foreground dark:text-white/45">{t.category}</p>
            <p title={t.desc} className="mt-3 line-clamp-3 text-xs leading-5 text-muted-foreground dark:text-white/50">
              {t.desc}
            </p>
            <div className="mt-auto flex items-center justify-between pt-4">
              <div className="flex gap-1.5">
                {t.tags.slice(0, 2).map((tag) => (
                  <span
                    key={tag}
                    className="rounded border border-border px-2 py-0.5 text-[10px] text-muted-foreground dark:border-white/[0.08] dark:text-white/45"
                  >
                    {tag}
                  </span>
                ))}
              </div>
              <Button size="sm" variant="outline" className="text-xs">
                Use template
              </Button>
            </div>
          </article>
        ))}
      </div>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, items.length)}
        totalCount={items.length}
        onLoadMore={() => setPageSize((p) => p + 24)}
      />
    </div>
  );
}
