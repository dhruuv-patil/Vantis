"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Check, ChevronsUpDown, Loader2, Plus } from "lucide-react";
import { toast } from "sonner";

import { getErrorMessage, switchOrganization } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { cn, initialsFrom } from "@/lib/utils";
import { CreateOrganizationDialog } from "@/components/app/create-organization-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

/**
 * The organization switcher. Switching calls the server
 * (`POST /api/organizations/{id}/switch`), which mints a new access token with
 * the new `orgId`; the store is updated from that response only — the client
 * never invents tenant context (contract §5.10).
 */
export function OrgSwitcher({ collapsed }: { collapsed?: boolean }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const current = useAuthStore((s) => s.currentOrganization);
  const memberships = useAuthStore((s) => s.memberships);
  const [switching, setSwitching] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  if (!current) return null;

  async function select(organizationId: string) {
    if (organizationId === current!.id || switching) return;
    setSwitching(organizationId);
    try {
      await switchOrganization(organizationId);
      // Clear all cached queries so no previous tenant data leaks or stays cached
      queryClient.clear();
      router.refresh();
    } catch (err) {
      toast.error(getErrorMessage(err, "Could not switch organization."));
    } finally {
      setSwitching(null);
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-lg border border-border bg-card px-2 py-1.5 text-left text-[13px] text-card-foreground transition-colors duration-200 hover:bg-muted focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 dark:border-white/[0.08] dark:bg-white/[0.03] dark:hover:bg-white/[0.06] dark:focus-visible:ring-white/[0.15]",
          collapsed && "justify-center px-0",
        )}
      >
        <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-muted text-[10px] font-semibold text-foreground/80 dark:bg-white/[0.06] dark:text-white/60">
          {initialsFrom(current.name)}
        </span>
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1 truncate font-medium text-foreground dark:text-white/80">
              {current.name}
            </span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground dark:text-white/30" />
          </>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64 border-border bg-popover text-popover-foreground dark:border-white/[0.08] dark:bg-[#0a0a0c]">
        <DropdownMenuLabel className="text-muted-foreground dark:text-white/50">Organizations</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {memberships.map((m) => (
          <DropdownMenuItem
            key={m.organizationId}
            onSelect={(e) => {
              e.preventDefault();
              void select(m.organizationId);
            }}
            className="gap-2"
          >
            <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-muted text-[10px] font-semibold text-foreground/80 dark:bg-white/[0.06] dark:text-white/60">
              {initialsFrom(m.organizationName)}
            </span>
            <span className="min-w-0 flex-1 truncate">{m.organizationName}</span>
            <span className="text-xs text-muted-foreground dark:text-white/40">{m.role}</span>
            {switching === m.organizationId ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              m.organizationId === current.id && (
                <Check className="size-4 text-foreground dark:text-white/70" />
              )
            )}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onSelect={() => setCreateOpen(true)}
          className="gap-2 cursor-pointer text-foreground dark:text-white/80 hover:bg-muted dark:hover:bg-white/[0.06]"
        >
          <Plus className="size-4" />
          Create workspace
        </DropdownMenuItem>
      </DropdownMenuContent>

      <CreateOrganizationDialog open={createOpen} onOpenChange={setCreateOpen} />
    </DropdownMenu>
  );
}
