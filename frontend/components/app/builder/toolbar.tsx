"use client";

import * as React from "react";
import {
  CheckCircle2,
  Maximize,
  Play,
  Redo2,
  Rocket,
  Save,
  Sparkles,
  Undo2,
  Webhook,
  ZoomIn,
  ZoomOut,
} from "lucide-react";

import { cn } from "@/lib/utils";
import type { WorkflowStatus } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

interface ToolbarProps {
  name: string;
  status: WorkflowStatus;
  version: number | null;
  dirty: boolean;
  canUndo: boolean;
  canRedo: boolean;
  savePending: boolean;
  validatePending: boolean;
  namePending: boolean;
  /** A published version exists, so there is something to run. */
  canRun: boolean;
  runPending: boolean;
  onRename: (name: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onValidate: () => void;
  onSave: () => void;
  onPublish: () => void;
  onRun: () => void;
  onGenerate: () => void;
  /** Open the inbound-webhook manager. Rendered only when the graph has a webhook trigger. */
  onWebhook: () => void;
  /** The graph contains a `webhook_trigger` node, so an inbound webhook is meaningful. */
  hasWebhookTrigger: boolean;
}

const statusVariant: Record<
  WorkflowStatus,
  "secondary" | "success" | "outline"
> = {
  DRAFT: "secondary",
  PUBLISHED: "success",
  ARCHIVED: "outline",
};

/**
 * Top bar: workflow identity + graph history + zoom + the lifecycle actions
 * (Validate / Save / Publish / Run). Presentational — all state lives on the
 * page; the toolbar just fires callbacks.
 */
export function Toolbar(props: ToolbarProps) {
  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-card px-3 dark:border-white/[0.075] dark:bg-[#0a0a0a]">
        <NameField
          name={props.name}
          pending={props.namePending}
          onRename={props.onRename}
        />

        <Badge variant={statusVariant[props.status]}>
          {props.status}
          {props.status === "PUBLISHED" && props.version != null && (
            <span className="ml-1 opacity-80">v{props.version}</span>
          )}
        </Badge>

        {/* Unsaved-changes indicator. */}
        <span
          className={cn(
            "flex items-center gap-1.5 text-xs text-muted-foreground transition-opacity",
            props.dirty ? "opacity-100" : "opacity-0",
          )}
          aria-live="polite"
        >
          <span className="size-2 rounded-full bg-amber-500" />
          Unsaved changes
        </span>

        <div className="ml-auto flex items-center gap-1">
          <IconAction
            label="Undo"
            onClick={props.onUndo}
            disabled={!props.canUndo}
          >
            <Undo2 className="size-4" />
          </IconAction>
          <IconAction
            label="Redo"
            onClick={props.onRedo}
            disabled={!props.canRedo}
          >
            <Redo2 className="size-4" />
          </IconAction>

          <Separator orientation="vertical" className="mx-1 h-6" />

          <IconAction label="Zoom in" onClick={props.onZoomIn}>
            <ZoomIn className="size-4" />
          </IconAction>
          <IconAction label="Zoom out" onClick={props.onZoomOut}>
            <ZoomOut className="size-4" />
          </IconAction>
          <IconAction label="Fit view" onClick={props.onFitView}>
            <Maximize className="size-4" />
          </IconAction>

          <Separator orientation="vertical" className="mx-1 h-6" />

          <Button
            variant="outline"
            size="sm"
            onClick={props.onGenerate}
            className="text-primary"
          >
            <Sparkles className="size-4" />
            Create with AI
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={props.onValidate}
            disabled={props.validatePending}
          >
            <CheckCircle2 className="size-4" />
            {props.validatePending ? "Validating…" : "Validate"}
          </Button>
          {props.hasWebhookTrigger && (
            <Button variant="outline" size="sm" onClick={props.onWebhook}>
              <Webhook className="size-4" />
              Webhook
            </Button>
          )}
          <Button
            variant="secondary"
            size="sm"
            onClick={props.onSave}
            disabled={props.savePending || !props.dirty}
          >
            <Save className="size-4" />
            {props.savePending ? "Saving…" : "Save"}
          </Button>
          <Button variant="contrast" size="sm" onClick={props.onPublish}>
            <Rocket className="size-4" />
            Publish
          </Button>

          <Tooltip>
            <TooltipTrigger asChild>
              {/* Span wrapper so the tooltip still fires while the button is
                  disabled (a disabled button swallows pointer events). */}
              <span tabIndex={props.canRun ? -1 : 0}>
                <Button
                  variant="default"
                  size="sm"
                  onClick={props.onRun}
                  disabled={!props.canRun || props.runPending}
                >
                  <Play className="size-4" />
                  {props.runPending ? "Starting…" : "Run"}
                </Button>
              </span>
            </TooltipTrigger>
            <TooltipContent>
              {props.canRun
                ? "Run the latest published version"
                : "Publish a version to run it"}
            </TooltipContent>
          </Tooltip>
        </div>
      </div>
    </TooltipProvider>
  );
}

function IconAction({
  label,
  onClick,
  disabled,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          onClick={onClick}
          disabled={disabled}
          aria-label={label}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}

/**
 * Inline-editable workflow name. Commits on blur / Enter via `updateWorkflow`;
 * Escape reverts. Kept local while editing so keystrokes don't fire a PATCH.
 */
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
      className="h-8 w-56 border-transparent bg-transparent px-2 text-sm font-semibold shadow-none hover:border-input focus-visible:border-input"
    />
  );
}
