"use client";

import React from "react";
import { Clock, Layers3, CheckCircle2, XCircle, Minus, Circle } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ExecutionStatus } from "@/types";

export interface ExecutionSummaryStripProps {
  durationMs?: number | null;
  totalNodes: number;
  executed: number;
  failed: number;
  skipped: number;
  running: number;
  status?: ExecutionStatus;
}

function formatMs(ms?: number | null): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

export function ExecutionSummaryStrip({
  durationMs,
  totalNodes,
  executed,
  failed,
  skipped,
  running,
  status,
}: ExecutionSummaryStripProps) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-white/[0.06] bg-[#070707] px-4 py-2.5 overflow-x-auto whitespace-nowrap">
      <div className="flex items-center gap-1.5 text-[11px] font-medium text-white/80">
        <Clock className="w-3.5 h-3.5 text-white/30" />
        <span>{formatMs(durationMs)}</span>
      </div>

      <div className="h-3 w-px bg-white/[0.08]" />

      <div className="flex items-center gap-1.5 text-[11px] text-white/60">
        <Layers3 className="w-3.5 h-3.5 text-white/20" />
        <span className="font-medium text-white/70">{totalNodes}</span>
        <span>nodes</span>
      </div>

      <div className="h-3 w-px bg-white/[0.08]" />

      {executed > 0 && (
        <div className="flex items-center gap-1 text-[11px] text-white/50">
          <CheckCircle2 className="w-3 h-3 text-emerald-400/60" />
          <span className="font-medium text-white/60">{executed}</span>
          <span>ok</span>
        </div>
      )}

      {failed > 0 && (
        <div className="flex items-center gap-1 text-[11px] text-red-300/70">
          <XCircle className="w-3 h-3 text-red-400/70" />
          <span className="font-medium text-red-300/80">{failed}</span>
          <span>failed</span>
        </div>
      )}

      {skipped > 0 && (
        <div className="flex items-center gap-1 text-[11px] text-white/45">
          <Minus className="w-3 h-3 text-white/40" />
          <span>{skipped}</span>
          <span>skipped</span>
        </div>
      )}

      {running > 0 && (
        <div className="flex items-center gap-1.5 text-[11px] text-blue-300/60">
          <Circle className="w-2.5 h-2.5 text-blue-300 animate-pulse" />
          <span>{running}</span>
          <span>running</span>
        </div>
      )}
    </div>
  );
}
