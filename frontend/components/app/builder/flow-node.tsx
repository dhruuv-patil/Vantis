"use client";

import * as React from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Check, Clock3, Loader2, X, Zap } from "lucide-react";

import { cn } from "@/lib/utils";
import type { NodeDefinition } from "@/types";
import {
  ErrorNodesContext,
  type FlowNodeData,
} from "@/components/app/builder/shared";
import {
  NodeIcon,
  resolveAccent,
} from "@/components/app/builder/node-visuals";

export const NodeDefsContext = React.createContext<
  Record<string, NodeDefinition>
>({});

const STATUS_BADGES: Record<
  string,
  { icon: React.ElementType; className: string; spin?: boolean }
> = {
  SUCCEEDED: { icon: Check, className: "bg-emerald-500" },
  FAILED: { icon: X, className: "bg-red-500" },
  RUNNING: { icon: Loader2, className: "bg-indigo-500", spin: true },
  WAITING: { icon: Clock3, className: "bg-blue-500" },
  QUEUED: { icon: Clock3, className: "bg-amber-500" },
};

/* ==========================================================================
   NODE
   ========================================================================== */

function FlowNodeComponent({ id, type, data, selected }: NodeProps) {
  const defs = React.useContext(NodeDefsContext);
  const errorNodes = React.useContext(ErrorNodesContext);

  const def = type ? defs[type] : undefined;
  const nodeData = data as FlowNodeData;

  const label = nodeData.label || def?.label || type || "Node";

  const outputs = def?.outputs?.length ? def.outputs : ["out"];

  const isTrigger = def?.trigger ?? false;
  const hasError = errorNodes.has(id);
  const multiOut = outputs.length > 1;

  const accent = resolveAccent(def?.category, isTrigger);
  const status = nodeData.executionStatus
    ? STATUS_BADGES[nodeData.executionStatus as string]
    : undefined;
  const isRunning = nodeData.executionStatus === "RUNNING";

  // Grow the node so multiple output ports have room
  const bodyMinHeight = Math.max(88, outputs.length * 30 + 20);

  const handleClass = cn(
    "!size-[10px] !rounded-full !border-2",
    "!border-white dark:!border-[#0a0a0a]",
    "!bg-zinc-400 dark:!bg-[#6f6f78]",
    "!shadow-[0_0_0_1px_rgba(0,0,0,0.08)] dark:!shadow-[0_0_0_1px_rgba(255,255,255,0.12)]",
    "transition-all duration-150",
    // larger invisible hit area, easier to connect
    "after:absolute after:-inset-2 after:content-['']",
    "hover:!scale-125 hover:!bg-brand-500 dark:hover:!bg-brand-400",
    "group-hover:!bg-zinc-500 dark:group-hover:!bg-[#9a9aa5]",
    selected && "!bg-brand-500 dark:!bg-brand-400",
    hasError && "!bg-red-500 dark:!bg-red-400",
  );

  return (
    <div className="group relative flex w-[132px] flex-col items-center">
      {/* ================================================================
          NODE BODY
          ================================================================ */}

      <div
        style={{ minHeight: bodyMinHeight }}
        className={cn(
          "relative flex w-[88px] shrink-0 items-center justify-center",
          "rounded-[10px]",
          isTrigger && "rounded-l-[28px]",
          "border border-zinc-200/90 bg-gradient-to-b from-white to-zinc-50 shadow-[0_2px_8px_rgba(0,0,0,0.06),0_1px_2px_rgba(0,0,0,0.04)]",
          "dark:border-white/[0.09] dark:bg-gradient-to-b dark:from-[#18181b] dark:to-[#101012] dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.06),0_6px_20px_rgba(0,0,0,0.35)]",
          "transition-all duration-150",

          "group-hover:-translate-y-px",
          "group-hover:border-zinc-300 dark:group-hover:border-white/[0.18]",

          selected && [
            "-translate-y-px border-brand-500/80 dark:border-brand-400/70",
            "shadow-[0_0_0_3px_rgba(99,102,241,0.22),0_8px_24px_rgba(0,0,0,0.12)] dark:shadow-[0_0_0_3px_rgba(99,102,241,0.18),0_8px_24px_rgba(0,0,0,0.4)]",
          ],

          nodeData.executionStatus === "SUCCEEDED" &&
            "border-emerald-500/60 shadow-[0_0_0_3px_rgba(52,211,153,0.15)] dark:border-emerald-400/50 dark:shadow-[0_0_0_3px_rgba(52,211,153,0.10),0_6px_20px_rgba(0,0,0,0.35)]",

          nodeData.executionStatus === "FAILED" &&
            "border-red-500/60 shadow-[0_0_0_3px_rgba(248,113,113,0.15)] dark:border-red-400/60 dark:shadow-[0_0_0_3px_rgba(248,113,113,0.12),0_6px_20px_rgba(0,0,0,0.35)]",

          hasError &&
            "!border-red-500 shadow-[0_0_0_3px_rgba(239,68,68,0.2)] dark:shadow-[0_0_0_3px_rgba(239,68,68,0.15),0_6px_20px_rgba(0,0,0,0.35)]",
        )}
      >
        {/* Running pulse ring */}
        {isRunning && (
          <span
            aria-hidden="true"
            className={cn(
              "pointer-events-none absolute -inset-[4px] animate-pulse",
              "rounded-[14px] border-2 border-indigo-400/60",
              isTrigger && "rounded-l-[32px]",
            )}
          />
        )}

        {/* Input handle */}
        {!isTrigger && (
          <Handle
            type="target"
            position={Position.Left}
            className={handleClass}
          />
        )}

        {/* Trigger bolt, outside the left edge like n8n */}
        {isTrigger && (
          <span
            aria-hidden="true"
            className="absolute -left-[22px] top-1/2 -translate-y-1/2 text-amber-400 drop-shadow-[0_0_6px_rgba(245,158,11,0.5)]"
          >
            <Zap className="size-[14px] fill-current" />
          </span>
        )}

        <NodeIcon
          type={type}
          label={label}
          icon={def?.icon}
          accent={accent}
          theme="auto"
        />

        {/* Corner badge: validation error beats execution status */}
        {hasError ? (
          <span
            className="absolute -right-[7px] -top-[7px] flex size-[18px] items-center justify-center rounded-full border-2 border-white bg-red-500 text-[10px] font-bold text-white dark:border-[#0a0a0a]"
            aria-label="Validation error"
          >
            !
          </span>
        ) : status ? (
          <span
            className={cn(
              "absolute -right-[7px] -top-[7px] flex size-[18px] items-center justify-center",
              "rounded-full border-2 border-white text-white dark:border-[#0a0a0a]",
              status.className,
            )}
          >
            <status.icon
              className={cn("size-[10px]", status.spin && "animate-spin")}
              strokeWidth={3}
            />
          </span>
        ) : null}

        {/* Single output */}
        {!multiOut && (
          <Handle
            id={outputs[0]}
            type="source"
            position={Position.Right}
            className={handleClass}
          />
        )}

        {/* Multiple outputs: evenly spaced, labels outside */}
        {multiOut &&
          outputs.map((out, index) => (
            <Handle
              key={out}
              id={out}
              type="source"
              position={Position.Right}
              style={{ top: `${((index + 1) / (outputs.length + 1)) * 100}%` }}
              className={handleClass}
            >
              <span
                className={cn(
                  "pointer-events-none absolute left-[18px] top-1/2 -translate-y-1/2",
                  "rounded border border-zinc-200 bg-zinc-100 px-1 py-px text-[9px] font-medium leading-none text-zinc-600 transition-colors group-hover:text-zinc-900",
                  "dark:border-transparent dark:bg-[#0a0a0a] dark:text-white/40 dark:group-hover:text-white/65",
                  "whitespace-nowrap",
                )}
              >
                {out}
              </span>
            </Handle>
          ))}
      </div>

      {/* ================================================================
          LABEL
          ================================================================ */}

      <div className="mt-2.5 w-[132px] text-center">
        <div
          className={cn(
            "truncate text-[13px] font-medium leading-5 text-zinc-800 transition-colors dark:text-white/85",
            selected && "text-black dark:text-white",
            hasError && "text-red-600 dark:text-red-300",
          )}
          title={label}
        >
          {label}
        </div>

        <div
          className="mt-px truncate text-[10px] font-medium uppercase tracking-[0.12em] text-zinc-500 dark:text-white/45"
          title={def?.category ?? type}
        >
          {def?.category ?? type}
        </div>
      </div>
    </div>
  );
}

export const FlowNode = React.memo(FlowNodeComponent);