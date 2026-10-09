"use client";

import { Suspense, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Loader2, UserPlus, X } from "lucide-react";
import { toast } from "sonner";

import {
  acceptInvitation,
  changeMemberRole,
  fetchCurrentOrganizationMembers,
  fetchInvitations,
  getErrorMessage,
  removeMember,
  revokeInvitation,
} from "@/lib/api";
import { hasRole, useAuthStore } from "@/lib/auth-store";
import { initialsFrom } from "@/lib/utils";
import type { Invitation, OrganizationMember, Role } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { InviteMemberDialog } from "@/components/app/invite-member-dialog";
import {
  EmptyState,
  ErrorState,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  TableSkeleton,
} from "@/components/app/page-primitives";

/** Roles assignable via the member row dropdown. OWNER is granted only by transfer, never here. */
const ASSIGNABLE_ROLES: Role[] = ["ADMIN", "MEMBER", "VIEWER"];

export default function TeamPage() {
  const role = useAuthStore((s) => s.currentRole);
  const isAdmin = hasRole(role, "ADMIN");

  const [inviteOpen, setInviteOpen] = useState(false);

  return (
    <PageContainer variant="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Team"
        description="Manage who belongs to this organization and what they can do."
        actions={
          isAdmin ? (
            <Button onClick={() => setInviteOpen(true)} className="gap-2">
              <UserPlus className="size-4" />
              <span>Invite member</span>
            </Button>
          ) : undefined
        }
      />

      <div className="space-y-8">
        {/* `useSearchParams` needs a Suspense boundary during prerender. */}
        <Suspense fallback={null}>
          <AcceptInvitationHandler />
        </Suspense>

        <MemberList isAdmin={isAdmin} />

        {isAdmin && (
          <>
            <Separator className="border-border dark:border-white/[0.065]" />
            <PendingInvitations />
          </>
        )}
      </div>

      <InviteMemberDialog open={inviteOpen} onOpenChange={setInviteOpen} />
    </PageContainer>
  );
}

/* ------------------------------------------------------------------ members */

function MemberList({ isAdmin }: { isAdmin: boolean }) {
  const currentUserId = useAuthStore((s) => s.user?.id ?? null);
  const [pageSize, setPageSize] = useState(25);
  const query = useQuery({
    queryKey: ["members"],
    queryFn: fetchCurrentOrganizationMembers,
  });

  if (query.isError) {
    return (
      <ErrorState
        title="Could not load members"
        message="Please check your connection and try again."
        onRetry={() => void query.refetch()}
      />
    );
  }

  if (query.isPending) {
    return <TableSkeleton rows={3} />;
  }

  const members = query.data.members;

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-medium text-muted-foreground dark:text-white/50">
        {members.length} member{members.length === 1 ? "" : "s"}
      </h2>
      <ul className="divide-y divide-border dark:divide-white/[0.05] overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
        {members.slice(0, pageSize).map((member) => (
          <MemberRow
            key={member.userId}
            member={member}
            isAdmin={isAdmin}
            isSelf={member.userId === currentUserId}
          />
        ))}
      </ul>

      <LoadMoreButton
        loadedCount={Math.min(pageSize, members.length)}
        totalCount={members.length}
        onLoadMore={() => setPageSize((prev) => prev + 25)}
      />
    </section>
  );
}

function MemberRow({
  member,
  isAdmin,
  isSelf,
}: {
  member: OrganizationMember;
  isAdmin: boolean;
  isSelf: boolean;
}) {
  const queryClient = useQueryClient();
  const [removeOpen, setRemoveOpen] = useState(false);

  const roleMutation = useMutation({
    mutationFn: (role: Role) => changeMemberRole(member.userId, role),
    onSuccess: (data) => {
      queryClient.setQueryData(["members"], data);
      toast.success("Role updated.");
    },
    onError: (err) => toast.error(getErrorMessage(err, "Could not change the role.")),
  });

  // OWNER rows and your own row are never editable here (backend enforces both:
  // an admin cannot touch an owner, and no one can change their own role).
  const canManage = isAdmin && !isSelf && member.role !== "OWNER";

  return (
    <li className="flex items-center gap-3 px-4 py-3.5 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.02]">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted dark:bg-white/[0.06] text-xs font-semibold text-foreground/80 dark:text-white/80">
        {initialsFrom(member.fullName)}
      </span>
      <div className="min-w-0 flex-1">
        <p
          title={member.fullName}
          className="truncate text-sm font-medium text-foreground dark:text-white/90"
        >
          {member.fullName}
          {isSelf && (
            <span className="ml-2 text-xs font-normal text-muted-foreground dark:text-white/40">
              (you)
            </span>
          )}
        </p>
        <p
          title={member.email}
          className="truncate text-xs text-muted-foreground dark:text-white/40"
        >
          {member.email}
        </p>
      </div>

      {canManage ? (
        <select
          aria-label={`Role for ${member.fullName}`}
          value={member.role}
          disabled={roleMutation.isPending}
          onChange={(e) => roleMutation.mutate(e.target.value as Role)}
          className="h-8 rounded-lg border border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0c] px-2 text-xs text-foreground dark:text-white/80 outline-none transition-colors hover:text-foreground dark:hover:text-white focus-visible:border-ring dark:focus-visible:border-white/20 disabled:opacity-50"
        >
          {ASSIGNABLE_ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      ) : (
        <Badge variant={member.role === "OWNER" ? "default" : "secondary"}>
          {member.role}
        </Badge>
      )}

      {canManage && (
        <Button
          variant="ghost"
          size="icon"
          aria-label={`Remove ${member.fullName}`}
          onClick={() => setRemoveOpen(true)}
          className="size-8 text-muted-foreground hover:text-red-500 dark:text-white/40 dark:hover:text-red-400"
        >
          <X className="size-4" />
        </Button>
      )}

      <RemoveMemberDialog
        member={member}
        open={removeOpen}
        onOpenChange={setRemoveOpen}
      />
    </li>
  );
}

function RemoveMemberDialog({
  member,
  open,
  onOpenChange,
}: {
  member: OrganizationMember;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => removeMember(member.userId),
    onSuccess: (data) => {
      queryClient.setQueryData(["members"], data);
      toast.success(`Removed ${member.fullName}.`);
      onOpenChange(false);
    },
    onError: (err) => toast.error(getErrorMessage(err, "Could not remove the member.")),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0c]">
        <DialogHeader>
          <DialogTitle>Remove {member.fullName}?</DialogTitle>
          <DialogDescription className="text-muted-foreground dark:text-white/50">
            They lose access to this organization immediately. Their workflows and
            past executions are preserved. This cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter className="gap-2 sm:gap-2">
          <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="destructive"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
          >
            {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
            Remove
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/* -------------------------------------------------------------- invitations */

function PendingInvitations() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["invitations"], queryFn: fetchInvitations });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeInvitation(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["invitations"] });
      toast.success("Invitation revoked.");
    },
    onError: (err) => toast.error(getErrorMessage(err, "Could not revoke.")),
  });

  if (query.isError) {
    return (
      <ErrorState
        title="Could not load invitations"
        message="Please check your connection and try again."
        onRetry={() => void query.refetch()}
      />
    );
  }

  if (query.isPending) {
    return <TableSkeleton rows={2} />;
  }

  const pending = query.data.invitations.filter((i) => i.status === "PENDING");

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-medium text-muted-foreground dark:text-white/50">
        Pending invitations
      </h2>
      {pending.length === 0 ? (
        <EmptyState
          icon={UserPlus}
          title="No pending invitations"
          description="Invite a teammate to get started."
        />
      ) : (
        <ul className="divide-y divide-border dark:divide-white/[0.05] overflow-hidden rounded-xl border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]">
          {pending.map((invitation) => (
            <InvitationRow
              key={invitation.id}
              invitation={invitation}
              onRevoke={() => revokeMutation.mutate(invitation.id)}
              revoking={revokeMutation.isPending}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function InvitationRow({
  invitation,
  onRevoke,
  revoking,
}: {
  invitation: Invitation;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const expired = new Date(invitation.expiresAt).getTime() < Date.now();
  return (
    <li className="flex items-center gap-3 px-4 py-3.5 transition-colors hover:bg-muted/40 dark:hover:bg-white/[0.02]">
      <div className="min-w-0 flex-1">
        <p
          title={invitation.email}
          className="truncate text-sm font-medium text-foreground dark:text-white/90"
        >
          {invitation.email}
        </p>
        <p className="truncate text-xs text-muted-foreground dark:text-white/40">
          {invitation.role} ·{" "}
          {expired
            ? "Expired"
            : `Expires ${new Date(invitation.expiresAt).toLocaleDateString()}`}
        </p>
      </div>
      <Badge variant={expired ? "warning" : "outline"}>
        {expired ? "Expired" : "Pending"}
      </Badge>
      <Button
        variant="ghost"
        size="sm"
        onClick={onRevoke}
        disabled={revoking}
        className="text-muted-foreground hover:text-red-500 dark:text-white/40 dark:hover:text-red-400"
      >
        Revoke
      </Button>
    </li>
  );
}

/* ------------------------------------------------- accept an invitation link */

/**
 * Redeems a `?invite=<token>` link for the signed-in user. The org and role come
 * only from the stored invitation; a mismatched email fails opaquely. On success
 * we prompt the user to switch into the org via the switcher (the backend does
 * not switch them automatically).
 */
function AcceptInvitationHandler() {
  const searchParams = useSearchParams();
  const token = searchParams.get("invite");
  const [dismissed, setDismissed] = useState(false);

  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => acceptInvitation(token!),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["organizations"] });
      toast.success(
        `Joined ${data.organization.name}. Switch to it from the org switcher.`,
      );
      setDismissed(true);
      // Drop the token from the URL so a refresh doesn't re-attempt it.
      window.history.replaceState(null, "", window.location.pathname);
    },
    onError: (err) => {
      toast.error(getErrorMessage(err, "This invitation link is not valid."));
      setDismissed(true);
      window.history.replaceState(null, "", window.location.pathname);
    },
  });

  // Fire exactly once when a token is present.
  useEffect(() => {
    if (token && !dismissed && mutation.isIdle) {
      mutation.mutate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  if (!token || dismissed) return null;

  return (
    <div className="flex items-center gap-2 rounded-lg border border-border dark:border-white/[0.08] bg-muted/30 dark:bg-white/[0.03] px-4 py-3 text-sm text-muted-foreground dark:text-white/44">
      <Loader2 className="size-4 animate-spin" />
      Redeeming your invitation…
    </div>
  );
}
