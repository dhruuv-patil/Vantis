"use client";

import React, { useMemo } from "react";
import { X, Clock, RotateCcw, AlertTriangle, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ExecutionNodeState, NodeRunStatus, ExecutionStatus } from "@/types";

export interface ExecutionInspectorProps {
  node: ExecutionNodeState | null;
  onRetry?: (nodeId: string) => void;
  onClose?: () => void;
  className?: string;
}

function statusMeta(status: NodeRunStatus | ExecutionStatus) {
  switch (status) {
    case "SUCCEEDED":
      return { label: "Succeeded", color: "text-emerald-400", icon: "✓" };
    case "FAILED":
      return { label: "Failed", color: "text-red-400", icon: "×" };
    case "RUNNING":
      return { label: "Running", color: "text-blue-300", icon: "◌" };
    case "WAITING":
      return { label: "Waiting", color: "text-amber-400", icon: "○" };
    case "QUEUED":
      return { label: "Queued", color: "text-muted-foreground dark:text-white/50", icon: "○" };
    case "SKIPPED":
      return { label: "Skipped", color: "text-muted-foreground/80 dark:text-white/40", icon: "—" };
    case "CANCELED":
      return { label: "Canceled", color: "text-muted-foreground dark:text-white/45", icon: "○" };
    default:
      return { label: "Pending", color: "text-muted-foreground dark:text-white/45", icon: "○" };
  }
}

function JsonBlock({ value, label }: { value: unknown; label?: string }) {
  const text = useMemo(() => {
    if (value === null || value === undefined) return null;
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }, [value]);

  if (text === null) {
    return (
      <div className="text-[12px] text-muted-foreground dark:text-white/45 italic">
        No data recorded
      </div>
    );
  }

  return (
    <div className="space-y-1">
      {label && (
        <div className="text-[11px] uppercase tracking-wider text-muted-foreground dark:text-white/45">
          {label}
        </div>
      )}
      <pre className="max-h-40 overflow-auto rounded-md bg-muted/50 dark:bg-black/40 p-3 text-[11px] leading-relaxed text-foreground/80 dark:text-white/70 font-mono border border-border dark:border-white/[0.06]">
        {text}
      </pre>
    </div>
  );
}

export function ExecutionInspector({
  node,
  onRetry,
  onClose,
  className,
}: ExecutionInspectorProps) {
  if (!node) {
    return (
      <div
        className={cn(
          "rounded-xl border border-border dark:border-white/[0.08] bg-card dark:bg-[#070707] p-5 text-muted-foreground dark:text-white/50 text-sm",
          className
        )}
      >
        <div className="flex items-center gap-2">
          <ChevronDown className="w-4 h-4" />
          Select a node to inspect
        </div>
      </div>
    );
  }

  const meta = statusMeta(node.status as NodeRunStatus | ExecutionStatus);
  const canRetry = node.status === "FAILED" && !!onRetry;

  return (
    <div
      className={cn(
        "rounded-xl border border-border dark:border-white/[0.08] bg-card dark:bg-[#070707] overflow-hidden",
        className
      )}
    >
      <div className="flex items-center justify-between gap-2 border-b border-border dark:border-white/[0.06] px-4 py-3">
        <div className="min-w-0">
          <div className="text-[13px] font-medium text-foreground dark:text-white/90 truncate">
            {node.label ?? node.nodeId}
          </div>
          <div className="text-[11px] uppercase tracking-wider text-muted-foreground dark:text-white/45 truncate">
            {node.nodeType}
          </div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className="shrink-0 flex h-6 w-6 items-center justify-center rounded-md border border-border dark:border-white/[0.08] text-muted-foreground dark:text-white/40 hover:text-foreground dark:hover:text-white hover:border-foreground/20 dark:hover:border-white/[0.15] transition"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      <div className="px-4 py-3 space-y-4">
        <div className="flex items-center gap-2">
          <span className={cn("text-sm font-medium", meta.color)}>
            {meta.icon}
          </span>
          <span className={cn("text-[12px] font-medium", meta.color)}>
            {meta.label}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-2 text-[11px]">
          <div className="rounded-md border border-border dark:border-white/[0.06] bg-muted/40 dark:bg-black/30 px-2.5 py-2">
            <div className="text-muted-foreground dark:text-white/45 mb-0.5">Duration</div>
            <div className="font-mono text-foreground/90 dark:text-white/80">
              {node.durationMs != null ? `${node.durationMs}ms` : "—"}
            </div>
          </div>
          <div className="rounded-md border border-border dark:border-white/[0.06] bg-muted/40 dark:bg-black/30 px-2.5 py-2">
            <div className="text-muted-foreground dark:text-white/45 mb-0.5">Attempt</div>
            <div className="font-mono text-foreground/90 dark:text-white/80">{node.attempt ?? 1}</div>
          </div>
        </div>

        <JsonBlock value={node.input} label="Input" />
        <JsonBlock value={node.output} label="Output" />

        {node.error && (
          <div className="space-y-1.5">
            <div className="flex items-center gap-1.5 text-[11px] uppercase tracking-wider text-red-600 dark:text-red-400">
              <AlertTriangle className="w-3 h-3" /> Error
            </div>
            <div className="rounded-md border border-red-500/20 bg-red-500/5 p-3 text-[11px] leading-relaxed text-red-600 dark:text-red-300/90 font-mono">
              {node.error}
            </div>
          </div>
        )}

        {node.attempts && node.attempts.length > 0 && (
          <div className="space-y-2">
            <div className="text-[11px] uppercase tracking-wider text-muted-foreground dark:text-white/45">
              Attempt History ({node.attempts.length})
            </div>
            <div className="space-y-1.5">
              {node.attempts.map((att) => (
                <div
                  key={att.id || att.attemptNumber}
                  className="rounded-md border border-border dark:border-white/[0.06] bg-muted/30 dark:bg-black/40 p-2.5 text-[11px] space-y-1"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-medium text-foreground/90 dark:text-white/80">
                      Attempt {att.attemptNumber}
                    </span>
                    <span
                      className={cn(
                        "px-1.5 py-0.5 rounded text-[10px] font-mono",
                        att.status === "SUCCEEDED"
                          ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20"
                          : att.status === "FAILED"
                          ? "bg-red-500/10 text-red-600 dark:text-red-400 border border-red-500/20"
                          : "bg-blue-500/10 text-blue-600 dark:text-blue-300 border border-blue-500/20"
                      )}
                    >
                      {att.status}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-muted-foreground dark:text-white/40 text-[10px]">
                    <span>{att.durationMs != null ? `${att.durationMs}ms` : "—"}</span>
                    <span>{new Date(att.startedAt).toLocaleTimeString()}</span>
                  </div>
                  {att.error && (
                    <div className="text-red-600 dark:text-red-300/80 font-mono text-[10px] truncate mt-1 bg-red-500/10 dark:bg-red-950/20 p-1 rounded">
                      {att.error}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {canRetry && (
          <button
            onClick={() => onRetry?.(node.nodeId)}
            className="w-full flex items-center justify-center gap-2 rounded-md border border-border dark:border-white/[0.12] bg-muted/40 dark:bg-white/[0.03] px-3 py-2 text-[12px] font-medium text-foreground/90 dark:text-white/80 transition hover:bg-muted dark:hover:bg-white/[0.07] hover:border-foreground/20 dark:hover:border-white/[0.18]"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            Retry run
          </button>
        )}
      </div>
    </div>
  );
}
