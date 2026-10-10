"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Check,
  Copy,
  Loader2,
  RefreshCw,
  Trash2,
  Webhook,
} from "lucide-react";
import { toast } from "sonner";

import {
  deleteWorkflowWebhook,
  fetchWorkflowWebhook,
  generateWorkflowWebhook,
  getErrorMessage,
  setWorkflowWebhookEnabled,
} from "@/lib/api";
import type { WebhookSecret } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface WebhookDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workflowId: string;
  /** ADMIN and above may generate/toggle/delete; others see the masked state read-only. */
  canManage: boolean;
}

/**
 * Manages a workflow's public inbound webhook.
 *
 * A generated token is shown exactly once. The full URL lives only in local state
 * until the dialog closes and is presented in a constrained, copyable block so long
 * URLs cannot overflow the modal.
 */
export function WebhookDialog({
  open,
  onOpenChange,
  workflowId,
  canManage,
}: WebhookDialogProps) {
  const queryClient = useQueryClient();
  const queryKey = ["workflow", workflowId, "webhook"];

  const [secret, setSecret] = React.useState<WebhookSecret | null>(null);
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const [copied, setCopied] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      setSecret(null);
      setConfirmDelete(false);
      setCopied(false);
    }
  }, [open]);

  const query = useQuery({
    queryKey,
    queryFn: () => fetchWorkflowWebhook(workflowId),
    enabled: open,
  });

  const webhook = query.data?.webhook ?? null;

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey });

  const generateMutation = useMutation({
    mutationFn: () => generateWorkflowWebhook(workflowId),

    onSuccess: (result) => {
      setSecret(result);
      setCopied(false);
      invalidate();

      toast.success(
        "Webhook URL generated. Copy it now — it is shown only once.",
      );
    },

    onError: (err) =>
      toast.error(
        getErrorMessage(err, "Could not generate the webhook."),
      ),
  });

  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) =>
      setWorkflowWebhookEnabled(workflowId, enabled),

    onSuccess: (result) => {
      invalidate();

      toast.success(
        result.webhook?.enabled
          ? "Webhook enabled."
          : "Webhook disabled.",
      );
    },

    onError: (err) =>
      toast.error(
        getErrorMessage(err, "Could not update the webhook."),
      ),
  });

  const deleteMutation = useMutation({
    mutationFn: () =>
      deleteWorkflowWebhook(workflowId),

    onSuccess: () => {
      setSecret(null);
      setConfirmDelete(false);
      setCopied(false);
      invalidate();

      toast.success("Webhook deleted.");
    },

    onError: (err) =>
      toast.error(
        getErrorMessage(err, "Could not delete the webhook."),
      ),
  });

  const busy =
    generateMutation.isPending ||
    toggleMutation.isPending ||
    deleteMutation.isPending;

  async function copySecretUrl() {
    if (!secret?.url) return;

    try {
      await navigator.clipboard.writeText(secret.url);
      setCopied(true);
      toast.success("Webhook URL copied.");

      window.setTimeout(() => {
        setCopied(false);
      }, 1800);
    } catch {
      toast.error(
        "Could not copy the URL. Copy it manually instead.",
      );
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-w-xl overflow-x-hidden"
      >
        <DialogHeader className="min-w-0 pr-8">
          <DialogTitle className="flex min-w-0 items-center gap-2">
            <Webhook className="size-4 shrink-0" />
            <span className="truncate">Inbound webhook</span>
          </DialogTitle>

          <DialogDescription className="min-w-0">
            Let an external system start this workflow by POSTing to a secret
            URL. The token authenticates the request; only its hash is stored,
            so the URL is shown once at generation and cannot be recovered
            later.
          </DialogDescription>
        </DialogHeader>

        {query.isLoading ? (
          <div className="flex items-center justify-center py-8 text-muted-foreground">
            <Loader2 className="size-5 animate-spin" />
          </div>
        ) : query.isError ? (
          <div className="rounded-lg border border-red-500/[0.12] bg-red-500/[0.035] px-3 py-3">
            <p className="text-sm text-red-600 dark:text-red-300/80">
              {getErrorMessage(
                query.error,
                "Could not load the webhook.",
              )}
            </p>
          </div>
        ) : (
          <div className="min-w-0 space-y-4">
            {/* One-time URL, shown only right after generating. */}
            {secret && (
              <section className="min-w-0 max-w-full overflow-hidden rounded-lg border border-amber-500/25 bg-amber-500/[0.07]">
                <div className="flex min-w-0 items-center justify-between gap-3 border-b border-amber-500/15 px-3 py-2.5">
                  <p className="flex min-w-0 items-center gap-2 text-xs font-medium text-amber-800 dark:text-amber-300/90">
                    <AlertTriangle className="size-4 shrink-0" />
                    <span className="truncate">
                      Copy this URL now — it is shown only once
                    </span>
                  </p>

                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={copySecretUrl}
                    className="h-7 shrink-0 border-amber-500/20 bg-amber-500/10 px-2.5 text-[10px] text-amber-800 hover:bg-amber-500/20 dark:border-amber-300/15 dark:bg-black/20 dark:text-amber-200/80 dark:hover:bg-black/30 dark:hover:text-amber-100"
                  >
                    {copied ? (
                      <>
                        <Check className="size-3" />
                        Copied
                      </>
                    ) : (
                      <>
                        <Copy className="size-3" />
                        Copy
                      </>
                    )}
                  </Button>
                </div>

                <div className="min-w-0 max-w-full bg-muted/50 dark:bg-black/45 p-3">
                  <code className="block min-w-0 max-w-full whitespace-normal break-all font-mono text-[11px] leading-5 text-foreground/80 dark:text-white/65">
                    {secret.url}
                  </code>
                </div>
              </section>
            )}

            {/* Current state. */}
            {webhook ? (
              <div className="flex min-w-0 items-start justify-between gap-3 rounded-lg border border-border bg-muted/20 p-3 dark:border-white/[0.08] dark:bg-white/[0.015]">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-mono text-sm text-foreground/85 dark:text-white/75">
                    Webhook ••••{webhook.tokenHint ?? ""}
                  </p>

                  <p className="mt-0.5 break-all text-xs leading-5 text-muted-foreground dark:text-white/50">
                    {webhook.urlMasked}
                  </p>
                </div>

                <Badge
                  variant={webhook.enabled ? "success" : "outline"}
                  className="shrink-0"
                >
                  {webhook.enabled
                    ? "Enabled"
                    : "Disabled"}
                </Badge>
              </div>
            ) : (
              <p className="rounded-lg border border-dashed border-border bg-muted/20 p-3 text-sm text-muted-foreground dark:border-white/[0.08] dark:bg-white/[0.012] dark:text-white/50">
                No webhook configured yet.
                {canManage
                  ? " Generate one to get a URL."
                  : " Ask an admin to generate one."}
              </p>
            )}

            {/* Honest scope note — no fake toggles. */}
            <p className="text-xs leading-5 text-muted-foreground dark:text-white/45">
              Requests are rate-limited and size-capped. HMAC signature
              verification and replay protection are coming in a later
              release.
            </p>
          </div>
        )}

        <DialogFooter className="flex-wrap gap-2">
          {canManage && webhook && !confirmDelete && (
            <>
              <Button
                type="button"
                variant="ghost"
                className="text-destructive"
                onClick={() => setConfirmDelete(true)}
                disabled={busy}
              >
                <Trash2 className="size-4" />
                Delete
              </Button>

              <Button
                type="button"
                variant="outline"
                onClick={() =>
                  toggleMutation.mutate(!webhook.enabled)
                }
                disabled={busy}
              >
                {webhook.enabled
                  ? "Disable"
                  : "Enable"}
              </Button>
            </>
          )}

          {canManage && confirmDelete && (
            <>
              <Button
                type="button"
                variant="ghost"
                onClick={() => setConfirmDelete(false)}
                disabled={deleteMutation.isPending}
              >
                Cancel
              </Button>

              <Button
                type="button"
                variant="destructive"
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
              >
                <Trash2 className="size-4" />
                {deleteMutation.isPending
                  ? "Deleting…"
                  : "Confirm delete"}
              </Button>
            </>
          )}

          {canManage && !confirmDelete && (
            <Button
              type="button"
              variant="contrast"
              onClick={() => generateMutation.mutate()}
              disabled={busy}
            >
              <RefreshCw className="size-4" />

              {generateMutation.isPending
                ? "Generating…"
                : webhook
                  ? "Regenerate"
                  : "Generate URL"}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
