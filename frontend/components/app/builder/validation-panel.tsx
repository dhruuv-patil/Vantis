"use client";

import * as React from "react";
import { AlertTriangle, X, XCircle } from "lucide-react";

import { cn } from "@/lib/utils";
import type { ValidationIssue, ValidationResult } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

interface ValidationPanelProps {
  result: ValidationResult | null;
  onClose: () => void;
  /** Focus the offending node on the canvas (setCenter / fitView on it). */
  onFocusNode: (nodeId: string) => void;
}

/**
 * Bottom drawer summarising the last validation run. Each issue is clickable
 * when it names a node, centering the canvas on it. Nothing is shown until a
 * validation has actually run (result === null).
 */
export function ValidationPanel({
  result,
  onClose,
  onFocusNode,
}: ValidationPanelProps) {
  if (!result) return null;

  const errors = result.issues.filter((i) => i.severity === "ERROR");
  const warnings = result.issues.filter((i) => i.severity === "WARNING");

  return (
    <div className="absolute inset-x-0 bottom-0 z-20 max-h-[45%] overflow-hidden rounded-t-lg border-t border-border bg-card shadow-lg dark:border-white/[0.075] dark:bg-[#0a0a0a]">
      <div className="flex items-center gap-2 border-b border-border px-4 py-2 dark:border-white/[0.075]">
        <span className="text-sm font-semibold">Validation</span>
        {result.valid ? (
          <Badge variant="success">Valid</Badge>
        ) : (
          <Badge variant="destructive">{errors.length} error(s)</Badge>
        )}
        {warnings.length > 0 && (
          <Badge variant="warning">{warnings.length} warning(s)</Badge>
        )}
        <Button
          variant="ghost"
          size="icon"
          className="ml-auto"
          onClick={onClose}
          aria-label="Close validation panel"
        >
          <X className="size-4" />
        </Button>
      </div>

      <div className="max-h-[calc(45vh-3rem)] overflow-y-auto p-2">
        {result.issues.length === 0 ? (
          <p className="px-2 py-4 text-center text-sm text-muted-foreground">
            No issues. This workflow is ready to publish.
          </p>
        ) : (
          <ul className="space-y-1">
            {result.issues.map((issue, i) => (
              <IssueRow
                key={`${issue.code}-${i}`}
                issue={issue}
                onFocusNode={onFocusNode}
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function IssueRow({
  issue,
  onFocusNode,
}: {
  issue: ValidationIssue;
  onFocusNode: (nodeId: string) => void;
}) {
  const isError = issue.severity === "ERROR";
  const clickable = Boolean(issue.nodeId);

  return (
    <li>
      <button
        type="button"
        disabled={!clickable}
        onClick={() => issue.nodeId && onFocusNode(issue.nodeId)}
        className={cn(
          "flex w-full items-start gap-2 rounded-md px-2 py-2 text-left text-sm transition-colors",
          clickable ? "hover:bg-muted dark:hover:bg-white/[0.06]" : "cursor-default",
        )}
      >
        {isError ? (
          <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" />
        ) : (
          <AlertTriangle className="mt-0.5 size-4 shrink-0 text-amber-500" />
        )}
        <span className="min-w-0 flex-1">
          <span className="block">{issue.message}</span>
          <span className="mt-0.5 flex flex-wrap items-center gap-2 font-mono text-[10px] text-muted-foreground">
            <span>{issue.code}</span>
            {issue.nodeId && <span>node: {issue.nodeId}</span>}
            {issue.edgeId && <span>edge: {issue.edgeId}</span>}
          </span>
        </span>
      </button>
    </li>
  );
}
