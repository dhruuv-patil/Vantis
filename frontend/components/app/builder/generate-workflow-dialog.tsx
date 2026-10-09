"use client";

import * as React from "react";
import { useMutation } from "@tanstack/react-query";
import { Loader2, Sparkles } from "lucide-react";
import { toast } from "sonner";

import { generateWorkflow, getErrorCode, getErrorMessage, getPlanLimitDetail } from "@/lib/api";
import type { GenerateWorkflowResult, PlanLimitDetail } from "@/types";
import { UpgradePrompt } from "@/components/billing/upgrade-prompt";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * "Create with AI": prompt → a draft graph. The result is only ever handed back
 * to the builder to drop on the canvas for review — this dialog NEVER runs the
 * workflow (contract §9: require explicit user confirmation). The user reviews,
 * edits, and publishes/runs manually.
 */
export function GenerateWorkflowDialog({
  open,
  onOpenChange,
  onAccept,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAccept: (result: GenerateWorkflowResult) => void;
}) {
  const [prompt, setPrompt] = React.useState("");
  const [result, setResult] = React.useState<GenerateWorkflowResult | null>(
    null,
  );
  const [notConfigured, setNotConfigured] = React.useState(false);
  const [limitDetail, setLimitDetail] = React.useState<PlanLimitDetail | null>(null);

  // Reset on each open so a previous run's result never leaks into a new one.
  React.useEffect(() => {
    if (open) {
      setPrompt("");
      setResult(null);
      setNotConfigured(false);
      setLimitDetail(null);
    }
  }, [open]);

  const mutation = useMutation({
    mutationFn: () => generateWorkflow(prompt.trim()),
    onSuccess: (res) => {
      setNotConfigured(false);
      setResult(res);
      setLimitDetail(null);
    },
    onError: (err) => {
      const code = getErrorCode(err);
      if (code === "AI_NOT_CONFIGURED") {
        setNotConfigured(true);
        setResult(null);
      } else {
        // Failure alert is never suppressed
        toast.error(
          getErrorMessage(err, "Could not generate a workflow. Try rewording."),
        );
      }

      // Structured plan limit details
      const limit = getPlanLimitDetail(err);
      if (limit) {
        setLimitDetail(limit);
      }
    },
  });

  function onGenerate() {
    if (!prompt.trim()) return;
    setNotConfigured(false);
    mutation.mutate();
  }

  const nodeCount = result?.graph.nodes.length ?? 0;
  const edgeCount = result?.graph.edges.length ?? 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Sparkles className="size-4 text-primary" />
            Create with AI
          </DialogTitle>
          <DialogDescription>
            Describe what you want to automate. The draft appears on the canvas
            for you to review and edit — it is never run automatically.
          </DialogDescription>
        </DialogHeader>

        {limitDetail && (
          <UpgradePrompt
            trigger="ai"
            structuredError={limitDetail}
            variant="inline"
            onDismiss={() => setLimitDetail(null)}
          />
        )}

        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="ai-prompt">Prompt</Label>
            <Textarea
              id="ai-prompt"
              autoFocus
              rows={5}
              value={prompt}
              disabled={mutation.isPending}
              placeholder="When a webhook arrives, call our CRM API, then send a Slack notification if it succeeded."
              onChange={(e) => setPrompt(e.target.value)}
            />
          </div>

          {notConfigured && (
            <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
              <p className="font-medium text-amber-600 dark:text-amber-400">
                AI is not configured
              </p>
              <p className="mt-1 text-muted-foreground">
                The AI service has no provider key. Set{" "}
                <code className="font-mono">GEMINI_API_KEY</code> on the AI
                service to enable generation. Nothing is fabricated.
              </p>
            </div>
          )}

          {result && (
            <div className="space-y-2 rounded-md border border-border/60 bg-card/40 p-3 text-sm">
              <p className="font-medium">
                Draft ready: {nodeCount} {nodeCount === 1 ? "node" : "nodes"},{" "}
                {edgeCount} {edgeCount === 1 ? "connection" : "connections"}
              </p>
              {result.notes && (
                <p className="text-muted-foreground">{result.notes}</p>
              )}
              <p className="text-[11px] text-muted-foreground">
                Adding replaces the current canvas. Nothing is saved or run until
                you choose to.
              </p>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="ghost"
            onClick={() => onOpenChange(false)}
          >
            Cancel
          </Button>
          {result ? (
            <>
              <Button
                type="button"
                variant="secondary"
                onClick={onGenerate}
                disabled={mutation.isPending}
              >
                {mutation.isPending && (
                  <Loader2 className="size-4 animate-spin" />
                )}
                Regenerate
              </Button>
              <Button
                type="button"
                onClick={() => {
                  onAccept(result);
                  onOpenChange(false);
                }}
              >
                Add to canvas
              </Button>
            </>
          ) : (
            <Button
              type="button"
              onClick={onGenerate}
              disabled={mutation.isPending || !prompt.trim()}
            >
              {mutation.isPending ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <Sparkles className="size-4" />
              )}
              Generate
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
