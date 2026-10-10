"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import {
  Play,
  Sparkles,
  Webhook,
  Save,
  Upload,
  Trash2,
  Loader2,
  CheckCircle2,
} from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { runWorkflow, updateWorkflow, getErrorMessage, getPlanLimitDetail } from "@/lib/api";
import type { PlanLimitDetail, WorkflowDetail, WorkflowStatus } from "@/types";
import { UpgradePrompt } from "@/components/billing/upgrade-prompt";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { SegmentedControl } from "@/components/ui/segmented-control";

type ActiveView = "editor" | "executions" | "anomalies";

interface WorkflowHeaderProps {
  workflow: WorkflowDetail;
  version: number | null;
  dirty: boolean;
  canRun: boolean;
  runPending?: boolean;
  namePending?: boolean;
  onRename?: (name: string) => void;
  onRun?: () => void;
  onValidate?: () => void;
  validatePending?: boolean;
  onGenerate: () => void;
  onSave: () => void;
  onPublish: () => void;
  onDelete: () => void;
  savePending: boolean;
  publishPending: boolean;
  deletePending: boolean;
  onWebhook: () => void;
  hasWebhookTrigger: boolean;
  activeView: ActiveView;
  onViewChange: (view: ActiveView) => void;
}

const statusConfig: Record<
  WorkflowStatus,
  { label: string; dot: string; className: string }
> = {
  DRAFT: {
    label: "Draft",
    dot: "bg-amber-500 dark:bg-amber-400",
    className: "border-amber-500/20 bg-amber-500/10 text-amber-800 dark:border-white/[0.10] dark:bg-white/[0.045] dark:text-white/60",
  },
  PUBLISHED: {
    label: "Published",
    dot: "bg-emerald-500 dark:bg-emerald-400",
    className: "border-emerald-500/20 bg-emerald-500/10 text-emerald-700 dark:border-emerald-400/20 dark:bg-emerald-400/[0.07] dark:text-emerald-300",
  },
  ARCHIVED: {
    label: "Archived",
    dot: "bg-muted-foreground/40 dark:bg-white/30",
    className: "border-border bg-muted/50 text-muted-foreground dark:border-white/[0.10] dark:bg-white/[0.035] dark:text-white/45",
  },
};

const views = [
  { value: "editor" as const, label: "Editor" },
  { value: "executions" as const, label: "Executions" },
  { value: "anomalies" as const, label: "Anomalies" },
];

function Divider() {
  return <div className="mx-1 h-5 w-px shrink-0 bg-border dark:bg-white/[0.08]" />;
}

export function WorkflowHeader({
  workflow,
  version,
  dirty,
  canRun,
  runPending,
  namePending,
  onRename,
  onRun,
  onValidate,
  validatePending = false,
  onGenerate,
  onSave,
  onPublish,
  onDelete,
  savePending,
  publishPending,
  deletePending,
  onWebhook,
  hasWebhookTrigger,
  activeView,
  onViewChange,
}: WorkflowHeaderProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [executionLimitDetail, setExecutionLimitDetail] =
    React.useState<PlanLimitDetail | null>(null);

  const runMutation = useMutation({
    mutationFn: () => runWorkflow(workflow.id),
    onSuccess: (execution) => {
      toast.success("Run started.");
      router.push(`/executions/${execution.id}`);
    },
    onError: (err) => {
      // 1. Failure alert is never suppressed
      toast.error(getErrorMessage(err));
      // 2. Structured limit detail consumed for upgrade prompt
      const limit = getPlanLimitDetail(err);
      if (limit) {
        setExecutionLimitDetail(limit);
      }
    },
  });

  const renameMutation = useMutation({
    mutationFn: (name: string) =>
      updateWorkflow(workflow.id, {
        name,
        description: workflow.description ?? undefined,
      }),
    onSuccess: (detail) => {
      queryClient.setQueryData(["workflow", workflow.id], detail);
      toast.success("Workflow renamed.");
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const running = runMutation.isPending || Boolean(runPending);
  const status = statusConfig[workflow.status];

  const handleRun = React.useCallback(() => {
    if (onRun) {
      onRun();
    } else {
      runMutation.mutate();
    }
  }, [onRun, runMutation]);

  return (
    <TooltipProvider delayDuration={250}>
      <header
        className={cn(
          "relative z-30 h-[60px] shrink-0",
          "border-b border-border dark:border-white/[0.07]",
          "bg-card/95 backdrop-blur-xl dark:bg-[#090909]/95",
        )}
      >
        <div className="flex h-full w-full items-center gap-4 px-4 lg:px-5">
          {/* ============ LEFT: identity (flexes + truncates) ============ */}
          <div className="flex min-w-0 flex-1 items-center gap-2">
            <NameField
              name={workflow.name}
              pending={namePending || renameMutation.isPending}
              onRename={(name) => renameMutation.mutate(name)}
            />

            <div
              className={cn(
                "flex shrink-0 items-center rounded-md border",
                "px-2 py-1 text-[11px] font-medium leading-none",
                status.className,
              )}
            >
              <span
                className={cn("mr-1.5 size-1.5 rounded-full", status.dot)}
              />
              <span>{status.label}</span>
              {workflow.status === "PUBLISHED" && version != null && (
                <span className="ml-1.5 text-muted-foreground dark:text-white/35">v{version}</span>
              )}
            </div>

            {dirty && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <span className="flex shrink-0 items-center">
                    <span className="size-1.5 rounded-full bg-amber-400" />
                  </span>
                </TooltipTrigger>
                <TooltipContent>You have unsaved changes</TooltipContent>
              </Tooltip>
            )}
          </div>

          {/* ============ CENTER: view toggle (never shrinks) ============ */}
          <div className="shrink-0 rounded-full [&>*]:rounded-full [&_button]:rounded-full">
            <SegmentedControl
              tabs={views}
              value={activeView}
              onValueChange={onViewChange}
            />
          </div>

          {/* ============ RIGHT: actions (never shrinks) ============ */}
          <div className="flex flex-1 shrink-0 items-center justify-end gap-1.5">
            {/* Create with AI */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={onGenerate}
                  aria-label="Create with AI"
                  className="size-8 shrink-0 text-violet-600 hover:bg-violet-500/10 hover:text-violet-700 dark:text-violet-300 dark:hover:bg-violet-400/[0.08] dark:hover:text-violet-200"
                >
                  <Sparkles className="size-4" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Create with AI</TooltipContent>
            </Tooltip>

            {/* Webhook (always visible; enabled only with a webhook trigger) */}
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="shrink-0">
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={onWebhook}
                    disabled={!hasWebhookTrigger}
                    aria-label="Manage webhook"
                    className="size-8 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40 dark:text-white/55 dark:hover:bg-white/[0.06] dark:hover:text-white"
                  >
                    <Webhook className="size-4" />
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {hasWebhookTrigger
                  ? "Manage inbound webhook"
                  : "Add a Webhook Trigger node to enable"}
              </TooltipContent>
            </Tooltip>

            <Divider />

            {/* Delete */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={onDelete}
                  disabled={deletePending}
                  aria-label="Delete workflow"
                  className="size-8 shrink-0 text-muted-foreground hover:bg-red-500/10 hover:text-red-600 dark:text-white/40 dark:hover:bg-red-500/[0.10] dark:hover:text-red-400"
                >
                  {deletePending ? (
                    <Loader2 className="size-4 animate-spin" />
                  ) : (
                    <Trash2 className="size-4" />
                  )}
                </Button>
              </TooltipTrigger>
              <TooltipContent>Delete workflow</TooltipContent>
            </Tooltip>

            {/* Save */}
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="shrink-0">
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={onSave}
                    disabled={!dirty || savePending}
                    aria-label="Save"
                    className="size-8 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40 dark:text-white/60 dark:hover:bg-white/[0.06] dark:hover:text-white"
                  >
                    {savePending ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <Save className="size-4" />
                    )}
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {dirty ? "Save workflow changes" : "No unsaved changes"}
              </TooltipContent>
            </Tooltip>

            {/* Validate */}
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="shrink-0">
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={onValidate}
                    disabled={validatePending}
                    aria-label="Validate workflow"
                    className="size-8 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40 dark:text-white/60 dark:hover:bg-white/[0.06] dark:hover:text-white"
                  >
                    {validatePending ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <CheckCircle2 className="size-4" />
                    )}
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {validatePending ? "Validating workflow…" : "Validate workflow"}
              </TooltipContent>
            </Tooltip>

            {/* Publish */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={onPublish}
                  disabled={publishPending}
                  className="h-8 shrink-0 gap-1.5 border-border bg-background px-3 text-foreground hover:bg-muted hover:text-foreground dark:border-white/[0.12] dark:bg-transparent dark:text-white dark:hover:bg-white/[0.08]"
                >
                  {publishPending ? (
                    <Loader2 className="size-3.5 animate-spin" />
                  ) : (
                    <Upload className="size-3.5" />
                  )}
                  {publishPending ? "Publishing" : "Publish"}
                </Button>
              </TooltipTrigger>
              <TooltipContent>Publish workflow</TooltipContent>
            </Tooltip>

            {/* Run (primary) */}
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="shrink-0">
                  <Button
                    size="sm"
                    onClick={running ? undefined : handleRun}
                    disabled={!canRun || running}
                    className="h-8 gap-1.5 bg-primary px-3.5 text-primary-foreground hover:bg-primary/90 disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/90 dark:disabled:bg-white/[0.08] dark:disabled:text-white/25"
                  >
                    {running ? (
                      <Loader2 className="size-3.5 animate-spin" />
                    ) : (
                      <Play className="size-3.5 fill-current" />
                    )}
                    {running ? "Running" : "Run"}
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {canRun
                  ? dirty
                    ? `Run latest published version (v${version}). Unsaved changes will not be included.`
                    : "Run the latest published version"
                  : "Publish a version before running"}
              </TooltipContent>
            </Tooltip>
          </div>
        </div>
      </header>

      {executionLimitDetail && (
        <UpgradePrompt
          trigger="executions"
          structuredError={executionLimitDetail}
          variant="dialog"
          open={!!executionLimitDetail}
          onOpenChange={(open) => {
            if (!open) setExecutionLimitDetail(null);
          }}
          onDismiss={() => setExecutionLimitDetail(null)}
        />
      )}
    </TooltipProvider>
  );
}

/* ================================================================
   Workflow name field
   ================================================================ */

function NameField({
  name,
  pending,
  onRename,
}: {
  name: string;
  pending: boolean;
  onRename: (name: string) => void;
}) {
  const [draft, setDraft] = React.useState(name);
  const [editing, setEditing] = React.useState(false);

  React.useEffect(() => {
    if (!editing) setDraft(name);
  }, [name, editing]);

  function commit() {
    setEditing(false);
    const trimmed = draft.trim();
    if (trimmed && trimmed !== name) {
      onRename(trimmed);
    } else {
      setDraft(name);
    }
  }

  return (
    <div className="min-w-0 max-w-[220px] shrink lg:max-w-[280px] xl:max-w-[340px]">
      <Input
        value={draft}
        disabled={pending}
        onFocus={() => setEditing(true)}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") e.currentTarget.blur();
          if (e.key === "Escape") {
            setDraft(name);
            setEditing(false);
            e.currentTarget.blur();
          }
        }}
        aria-label="Workflow name"
        className={cn(
          "h-9 w-full min-w-0 truncate px-2",
          "border-transparent bg-transparent shadow-none",
          "text-[13px] font-semibold tracking-[-0.01em] text-foreground dark:text-white",
          "hover:border-border hover:bg-muted/40 dark:hover:border-white/[0.07] dark:hover:bg-white/[0.025]",
          "focus:border-primary/30 focus:bg-background dark:focus:border-white/[0.12] dark:focus:bg-white/[0.035]",
          "focus-visible:ring-0 focus-visible:ring-offset-0",
        )}
      />
    </div>
  );
}