"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Check, ChevronsUpDown, Loader2, Plus } from "lucide-react";
import { toast } from "sonner";

import { getErrorMessage, switchOrganization } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { cn, initialsFrom } from "@/lib/utils";
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
  const current = useAuthStore((s) => s.currentOrganization);
  const memberships = useAuthStore((s) => s.memberships);
  const [switching, setSwitching] = useState<string | null>(null);

  if (!current) return null;

  async function select(organizationId: string) {
    if (organizationId === current!.id || switching) return;
    setSwitching(organizationId);
    try {
      await switchOrganization(organizationId);
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
          "flex w-full items-center gap-2 rounded-lg border border-white/[0.08] bg-white/[0.03] px-2 py-1.5 text-left text-[13px] transition-colors duration-200 hover:bg-white/[0.06] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-white/[0.15]",
          collapsed && "justify-center px-0",
        )}
      >
        <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-white/[0.06] text-[10px] font-semibold text-white/60">
          {initialsFrom(current.name)}
        </span>
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1 truncate font-medium text-white/80">
              {current.name}
            </span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-white/30" />
          </>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64 border-white/[0.08] bg-[#0a0a0c]">
        <DropdownMenuLabel className="text-white/50">Organizations</DropdownMenuLabel>
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
            <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-white/[0.06] text-[10px] font-semibold text-white/60">
              {initialsFrom(m.organizationName)}
            </span>
            <span className="min-w-0 flex-1 truncate">{m.organizationName}</span>
            <span className="text-xs text-white/40">{m.role}</span>
            {switching === m.organizationId ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              m.organizationId === current.id && (
                <Check className="size-4 text-white/70" />
              )
            )}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem disabled className="gap-2 text-white/45">
          <Plus className="size-4" />
          New organization
          <span className="ml-auto text-xs">Soon</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
