"use client";

import Link from "next/link";
import { useState } from "react";
import {
  keepPreviousData,
  useQuery,
} from "@tanstack/react-query";
import {
  FileText,
  Search,
  ShieldCheck,
} from "lucide-react";

import {
  fetchAuditLogs,
  fetchLogs,
} from "@/lib/api";
import {
  hasRole,
  useAuthStore,
} from "@/lib/auth-store";
import {
  formatRelativeTime,
} from "@/lib/format";
import type {
  AuditLogEntry,
  LogEntry,
  LogLevel,
} from "@/types";
import { cn } from "@/lib/utils";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SegmentedControl, SegmentedToggle } from "@/components/ui/segmented-control";
import {
  EmptyState,
  ErrorState,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  TableSkeleton,
  Toolbar,
} from "@/components/app/page-primitives";

const LEVEL_FILTERS: {
  label: string;
  value: LogLevel | "ALL";
}[] = [
  { label: "All", value: "ALL" },
  { label: "Info", value: "INFO" },
  { label: "Warn", value: "WARN" },
  { label: "Error", value: "ERROR" },
];

export default function LogsPage() {
  const role = useAuthStore((s) => s.currentRole);
  const isAdmin = hasRole(role, "ADMIN");
  const [tab, setTab] = useState<"runs" | "audit">("runs");

  return (
    <PageContainer variant="wide">
      {/* Page header */}
      <PageHeader
        eyebrow="Observability"
        title="Logs"
        description="Everything this workspace has recorded — run output and, for admins, the audit trail."
        actions={
          isAdmin ? (
            <SegmentedToggle
              label="Log source"
              value={tab}
              onValueChange={(v) => setTab(v as "runs" | "audit")}
              options={[
                {
                  value: "runs",
                  label: "Run logs",
                  icon: <FileText className="size-3.5" />,
                },
                {
                  value: "audit",
                  label: "Audit trail",
                  icon: <ShieldCheck className="size-3.5" />,
                },
              ]}
            />
          ) : undefined
        }
      />

      <div className="mt-6">
        {tab === "audit" && isAdmin ? <AuditTrail /> : <RunLogs />}
      </div>
    </PageContainer>
  );
}

/* =========================================================
   RUN LOGS
   ========================================================= */

function RunLogs() {
  const [level, setLevel] = useState<LogLevel | "ALL">("ALL");
  const [search, setSearch] = useState("");
  const [pageSize, setPageSize] = useState(50);

  const query = useQuery({
    queryKey: ["logs", { level }],
    queryFn: () =>
      fetchLogs({
        level: level === "ALL" ? undefined : level,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });

  const logs = query.data?.logs ?? [];

  const filteredLogs = logs.filter((l) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      l.message.toLowerCase().includes(q) ||
      (l.workflowName && l.workflowName.toLowerCase().includes(q)) ||
      (l.nodeId && l.nodeId.toLowerCase().includes(q))
    );
  });

  return (
    <div className="space-y-4">
      {/* Standard Toolbar */}
      <Toolbar className="mb-4">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground dark:text-white/40" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search log messages or workflows…"
            className="pl-10 text-sm"
          />
        </div>

        <SegmentedControl
          value={level}
          onValueChange={(v) => setLevel(v as LogLevel | "ALL")}
          label="Filter by log level"
          options={LEVEL_FILTERS.map((f) => ({
            value: f.value,
            label: f.label,
          }))}
        />
      </Toolbar>

      {/* Loading & States */}
      {query.isPending ? (
        <TableSkeleton rows={6} />
      ) : query.isError ? (
        <ErrorState
          title="Could not load logs"
          message="Please check your connection and try again."
          onRetry={() => void query.refetch()}
        />
      ) : filteredLogs.length === 0 ? (
        <EmptyState
          icon={FileText}
          title={
            search.trim()
              ? `No logs match "${search}"`
              : level !== "ALL"
                ? "No log lines at this level."
                : "No run logs yet."
          }
          description={
            search.trim()
              ? "Try adjusting your search query or log level filter."
              : "Run a published workflow and its log lines will appear here."
          }
          actions={
            search.trim() ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setSearch("");
                  setLevel("ALL");
                }}
              >
                Clear filters
              </Button>
            ) : (
              <Link href="/workflows">
                <Button size="sm">Go to workflows</Button>
              </Link>
            )
          }
        />
      ) : (
        <>
          <ul className="divide-y divide-border dark:divide-white/[0.05] overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
            {filteredLogs.slice(0, pageSize).map((entry) => (
              <LogRow key={entry.id} entry={entry} />
            ))}
          </ul>

          <LoadMoreButton
            loadedCount={Math.min(pageSize, filteredLogs.length)}
            totalCount={filteredLogs.length}
            onLoadMore={() => setPageSize((prev) => prev + 50)}
          />
        </>
      )}
    </div>
  );
}

/* =========================================================
   LOG LEVEL STYLES
   ========================================================= */

const LEVEL_TONE: Record<LogLevel, string> = {
  DEBUG: "text-muted-foreground dark:text-white/40",
  INFO: "text-foreground/90 dark:text-white/85",
  WARN: "text-amber-600 dark:text-amber-400",
  ERROR: "text-red-600 dark:text-red-400",
};

/* =========================================================
   LOG ROW
   ========================================================= */

function LogRow({ entry }: { entry: LogEntry }) {
  return (
    <li>
      <Link
        href={`/executions/${entry.executionId}`}
        className="flex items-start gap-3 px-4 py-3 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.02] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring dark:focus-visible:ring-white/20"
      >
        <span
          className={cn(
            "w-14 shrink-0 pt-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider",
            LEVEL_TONE[entry.level]
          )}
        >
          {entry.level}
        </span>

        <span className="min-w-0 flex-1">
          <span
            title={entry.message}
            className="block break-words font-mono text-xs leading-relaxed text-foreground/80 dark:text-white/80"
          >
            {entry.message}
          </span>

          <span
            title={`${entry.workflowName ?? ""}${entry.nodeId ? ` · node ${entry.nodeId}` : ""}`}
            className="mt-1 block truncate text-xs text-muted-foreground dark:text-white/40"
          >
            {entry.workflowName}
            {entry.nodeId ? ` · node ${entry.nodeId}` : ""}
          </span>
        </span>

        <span className="hidden shrink-0 text-right font-mono text-xs text-muted-foreground dark:text-white/40 sm:block">
          {formatRelativeTime(entry.createdAt)}
        </span>
      </Link>
    </li>
  );
}

/* =========================================================
   AUDIT TRAIL
   ========================================================= */

function AuditTrail() {
  const [pageSize, setPageSize] = useState(50);
  const query = useQuery({
    queryKey: ["audit-logs"],
    queryFn: () => fetchAuditLogs(),
    placeholderData: keepPreviousData,
  });

  const entries = query.data?.auditLogs ?? [];

  if (query.isPending) {
    return <TableSkeleton rows={6} />;
  }

  if (query.isError) {
    return (
      <ErrorState
        title="Could not load audit trail"
        message="Please check your connection and try again."
        onRetry={() => void query.refetch()}
      />
    );
  }

  if (entries.length === 0) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="No audited actions yet."
        description="Invites, role changes, integrations and webhook activity are recorded here."
      />
    );
  }

  return (
    <>
      <ul className="divide-y divide-border dark:divide-white/[0.05] overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
        {entries.slice(0, pageSize).map((entry) => (
          <AuditRow key={entry.id} entry={entry} />
        ))}
      </ul>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, entries.length)}
        totalCount={entries.length}
        onLoadMore={() => setPageSize((prev) => prev + 50)}
      />
    </>
  );
}

/* =========================================================
   AUDIT ROW
   ========================================================= */

function AuditRow({ entry }: { entry: AuditLogEntry }) {
  const actor = entry.actorEmail ?? "anonymous";
  const failed = entry.action === "WEBHOOK_AUTH_FAILED";

  return (
    <li className="flex items-start gap-3 px-4 py-3.5 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.02]">
      <Badge
        variant={failed ? "destructive" : "secondary"}
        className="shrink-0 font-mono text-[10px]"
      >
        {entry.action.toLowerCase().replace(/_/g, " ")}
      </Badge>

      <span className="min-w-0 flex-1">
        <span
          title={entry.summary ?? undefined}
          className="block text-sm text-foreground/90 dark:text-white/85"
        >
          {entry.summary}
        </span>
        <span
          title={`${actor}${entry.ip ? ` · ${entry.ip}` : ""}${entry.targetType ? ` · ${entry.targetType}` : ""}`}
          className="mt-0.5 block truncate text-xs text-muted-foreground dark:text-white/40"
        >
          {actor}
          {entry.ip ? ` · ${entry.ip}` : ""}
          {entry.targetType ? ` · ${entry.targetType}` : ""}
        </span>
      </span>

      <span className="hidden shrink-0 text-right font-mono text-xs text-muted-foreground dark:text-white/40 sm:block">
        {formatRelativeTime(entry.createdAt)}
      </span>
    </li>
  );
}