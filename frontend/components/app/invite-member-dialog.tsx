"use client";

import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Loader2 } from "lucide-react";
import { toast } from "sonner";

import {
  getErrorMessage,
  getFieldErrors,
  getPlanLimitDetail,
  inviteMember,
} from "@/lib/api";
import type { InvitationSecret, PlanLimitDetail, Role } from "@/types";
import { UpgradePrompt } from "@/components/billing/upgrade-prompt";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

const INVITABLE_ROLES: Role[] = ["ADMIN", "MEMBER", "VIEWER"];

export function InviteMemberDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();

  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("MEMBER");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [secret, setSecret] = useState<InvitationSecret | null>(null);
  const [copied, setCopied] = useState(false);
  const [limitDetail, setLimitDetail] = useState<PlanLimitDetail | null>(null);

  const inviteLink =
    secret && typeof window !== "undefined"
      ? `${window.location.origin}/team?invite=${encodeURIComponent(secret.token)}`
      : "";

  const mutation = useMutation({
    mutationFn: () =>
      inviteMember({
        email: email.trim(),
        role,
      }),

    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: ["invitations"],
      });

      setSecret(data);
      setCopied(false);
      setLimitDetail(null);

      toast.success("Invitation created.");
    },

    onError: (err) => {
      // 1. Failure alerts are never blocked
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      } else {
        toast.error(
          getErrorMessage(
            err,
            "Could not create the invitation.",
          ),
        );
      }

      // 2. Structured limit details for seats trigger
      const limit = getPlanLimitDetail(err);
      if (limit) {
        setLimitDetail(limit);
      }
    },
  });

  function reset() {
    setEmail("");
    setRole("MEMBER");
    setErrors({});
    setSecret(null);
    setCopied(false);
    setLimitDetail(null);
    mutation.reset();
  }

  function close() {
    reset();
    onOpenChange(false);
  }

  async function copyInviteLink() {
    if (!inviteLink) return;

    try {
      await navigator.clipboard.writeText(inviteLink);

      setCopied(true);
      toast.success("Invitation link copied.");

      window.setTimeout(() => {
        setCopied(false);
      }, 1800);
    } catch {
      toast.error(
        "Could not copy the invitation link. Copy it manually instead.",
      );
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});

    if (!email.trim()) {
      setErrors({
        email: "Enter an email address to invite.",
      });
      return;
    }

    mutation.mutate();
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          reset();
        }

        onOpenChange(next);
      }}
    >
      <DialogContent className="max-w-lg overflow-x-hidden">
        {secret ? (
          <>
            <DialogHeader className="min-w-0 pr-8">
              <DialogTitle className="text-foreground dark:text-white/90">
                Invitation ready
              </DialogTitle>

              <DialogDescription className="min-w-0 text-muted-foreground dark:text-white/40">
                Share this link with{" "}
                <strong className="text-foreground/90 dark:text-white/65">
                  {secret.email}
                </strong>
                . They redeem it while signed in with that email. It is
                shown once, so copy it now.
              </DialogDescription>
            </DialogHeader>

            <div className="min-w-0 space-y-3 py-2">
              <div className="min-w-0 max-w-full overflow-hidden rounded-lg border border-border dark:border-white/[0.08] bg-muted/20 dark:bg-white/[0.018]">
                <div className="flex min-w-0 items-center justify-between gap-3 border-b border-border dark:border-white/[0.06] px-3 py-2.5">
                  <p className="min-w-0 text-xs font-medium text-foreground/80 dark:text-white/60">
                    Invitation link
                  </p>

                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={copyInviteLink}
                    className="h-7 shrink-0 gap-1.5 px-2.5 text-[10px]"
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

                <div className="min-w-0 max-w-full bg-muted/60 dark:bg-black/35 p-3">
                  <code className="block min-w-0 max-w-full whitespace-normal break-all font-mono text-[11px] leading-5 text-foreground/80 dark:text-white/60">
                    {inviteLink}
                  </code>
                </div>
              </div>

              <p className="text-xs leading-5 text-muted-foreground dark:text-white/50">
                Grants the{" "}
                <strong className="text-foreground dark:text-white/80">
                  {secret.role}
                </strong>{" "}
                role. Expires{" "}
                {new Date(secret.expiresAt).toLocaleDateString()}.
              </p>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="ghost"
                onClick={reset}
              >
                Invite another
              </Button>

              <Button
                type="button"
                onClick={close}
              >
                Done
              </Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={onSubmit}>
            <DialogHeader className="pr-8">
              <DialogTitle className="text-foreground dark:text-white/90">
                Invite a member
              </DialogTitle>

              <DialogDescription className="text-muted-foreground dark:text-white/40">
                We generate a one-time invitation link — no email is sent.
                Bound to the address you enter; only that account can redeem
                it.
              </DialogDescription>
            </DialogHeader>

            {limitDetail && (
              <UpgradePrompt
                trigger="seats"
                structuredError={limitDetail}
                variant="inline"
                onDismiss={() => setLimitDetail(null)}
              />
            )}

            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="invite-email">
                  Email
                </Label>

                <Input
                  id="invite-email"
                  autoFocus
                  type="email"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);

                    if (errors.email) {
                      setErrors((current) => {
                        const next = {
                          ...current,
                        };

                        delete next.email;

                        return next;
                      });
                    }
                  }}
                  placeholder="teammate@company.com"
                  aria-invalid={Boolean(errors.email)}
                />

                {errors.email && (
                  <p className="text-xs text-destructive">
                    {errors.email}
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="invite-role">
                  Role
                </Label>

                <select
                  id="invite-role"
                  value={role}
                  onChange={(e) =>
                    setRole(e.target.value as Role)
                  }
                  className="flex h-9 w-full rounded-md border border-input bg-background dark:bg-transparent text-foreground px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                >
                  {INVITABLE_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="ghost"
                onClick={close}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>

              <Button
                type="submit"
                disabled={mutation.isPending}
              >
                {mutation.isPending && (
                  <Loader2 className="size-4 animate-spin" />
                )}
                {mutation.isPending
                  ? "Creating..."
                  : "Create invitation"}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
