"use client";

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import { Command } from "cmdk";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Bot,
  FileText,
  LayoutDashboard,
  PanelsTopLeft,
  Plug,
  Plus,
  Search,
  Settings,
  Users,
  Workflow,
  type LucideIcon,
} from "lucide-react";

import { fetchAiAgents, fetchTemplates, fetchWorkflows } from "@/lib/api";
import { hasRole, useAuthStore } from "@/lib/auth-store";
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * The ⌘K command palette.
 *
 * Every entry performs a REAL action against real data: static entries navigate
 * to routes that exist, and the search groups are populated from the same
 * endpoints the list pages use ({@link fetchWorkflows}, {@link fetchAiAgents},
 * {@link fetchTemplates}), so selecting a result lands on that exact resource.
 * Nothing here is a placeholder, and nothing executes a workflow.
 *
 * Data is fetched only while the palette is open and cached by TanStack Query, so
 * opening it does not fan out requests on every page. Keyboard navigation and
 * type-ahead filtering come from `cmdk`; Escape / selection close the dialog.
 */
interface Destination {
  id: string;
  label: string;
  hint?: string;
  icon: LucideIcon;
  href: string;
  keywords?: string[];
}

const NAV: Destination[] = [
  { id: "nav-dashboard", label: "Dashboard", icon: LayoutDashboard, href: "/dashboard" },
  { id: "nav-workflows", label: "Workflows", icon: Workflow, href: "/workflows" },
  { id: "nav-executions", label: "Executions", icon: Activity, href: "/executions", keywords: ["runs"] },
  { id: "nav-templates", label: "Templates", icon: PanelsTopLeft, href: "/templates" },
  { id: "nav-reliability", label: "Anomalies", icon: AlertTriangle, href: "/reliability", keywords: ["reliability", "incidents", "alerts"] },
  { id: "nav-agents", label: "AI Agents", icon: Bot, href: "/agents", keywords: ["ai"] },
  { id: "nav-integrations", label: "Integrations", icon: Plug, href: "/integrations", keywords: ["slack"] },
  { id: "nav-logs", label: "Logs", icon: FileText, href: "/logs", keywords: ["audit"] },
  { id: "nav-team", label: "Team", icon: Users, href: "/team", keywords: ["members", "invite"] },
  { id: "nav-settings", label: "Settings", icon: Settings, href: "/settings" },
];

export function CommandPalette({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const role = useAuthStore((s) => s.currentRole);
  const canManageTeam = hasRole(role, "ADMIN");

  // Only fetch while open; the list pages prime the same cache keys, so this is
  // usually a cache hit rather than a fresh round trip.
  const workflows = useQuery({
    queryKey: ["workflows", {}],
    queryFn: () => fetchWorkflows(),
    enabled: open,
  });
  const agents = useQuery({
    queryKey: ["ai-agents"],
    queryFn: fetchAiAgents,
    enabled: open,
  });
  const templates = useQuery({
    queryKey: ["templates"],
    queryFn: fetchTemplates,
    enabled: open,
  });

  const navItems = useMemo(
    () => NAV.filter((n) => (n.href === "/team" ? canManageTeam : true)),
    [canManageTeam],
  );

  function go(href: string) {
    onOpenChange(false);
    router.push(href);
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="overflow-hidden p-0 sm:max-w-lg border-border bg-card dark:border-white/[0.08] dark:bg-[#0a0a0c]"
        // The command input is the first focusable element; let cmdk own arrow keys.
      >
        <DialogTitle className="sr-only">Command palette</DialogTitle>
        <Command
          loop
          className="flex max-h-[70vh] flex-col"
          filter={(value, search, keywords) => {
            const haystack = (value + " " + (keywords?.join(" ") ?? "")).toLowerCase();
            return haystack.includes(search.toLowerCase()) ? 1 : 0;
          }}
        >
          <div className="flex items-center gap-2 border-b border-border dark:border-white/[0.08] px-3">
            <Search className="size-4 shrink-0 text-muted-foreground dark:text-white/40" />
            <Command.Input
  autoFocus
  placeholder="Search workflows, agents, templates…"
  className="
    h-11
    w-full
    border-0
    bg-transparent
    text-sm
    text-foreground
    placeholder:text-muted-foreground
    dark:text-white/80
    dark:placeholder:text-white/45
    outline-none
    ring-0
    shadow-none
    focus:outline-none
    focus:ring-0
    focus:ring-offset-0
    focus-visible:outline-none
    focus-visible:ring-0
    focus-visible:ring-offset-0
  "
/>
          </div>

          <Command.List className="overflow-y-auto p-1">
            <Command.Empty className="px-3 py-6 text-center text-sm text-muted-foreground dark:text-white/35">
              No matches.
            </Command.Empty>

            <Command.Group heading="Go to" className={GROUP}>
              {navItems.map((item) => {
                const Icon = item.icon;
                return (
                  <Command.Item
                    key={item.id}
                    value={`${item.label} ${item.keywords?.join(" ") ?? ""}`}
                    onSelect={() => go(item.href)}
                    className={ITEM}
                  >
                    <Icon className="size-4 shrink-0 text-muted-foreground dark:text-white/40" />
                    <span className="flex-1">{item.label}</span>
                  </Command.Item>
                );
              })}
            </Command.Group>

            <Command.Group heading="Create" className={GROUP}>
              <Command.Item
                value="New workflow create blank"
                onSelect={() => go("/workflows?new=1")}
                className={ITEM}
              >
                <Plus className="size-4 shrink-0 text-muted-foreground" />
                <span className="flex-1">New workflow</span>
              </Command.Item>
              <Command.Item
                value="New AI agent create"
                onSelect={() => go("/agents?new=1")}
                className={ITEM}
              >
                <Plus className="size-4 shrink-0 text-muted-foreground" />
                <span className="flex-1">New AI agent</span>
              </Command.Item>
            </Command.Group>

            {(workflows.data?.workflows.length ?? 0) > 0 && (
              <Command.Group heading="Workflows" className={GROUP}>
                {workflows.data!.workflows.map((w) => (
                  <Command.Item
                    key={w.id}
                    value={`workflow ${w.name} ${w.description ?? ""}`}
                    onSelect={() => go(`/workflows/${w.id}`)}
                    className={ITEM}
                  >
                    <Workflow className="size-4 shrink-0 text-muted-foreground" />
                    <span className="flex-1 truncate">{w.name}</span>
                    <span className="shrink-0 text-[10px] uppercase text-muted-foreground">
                      {w.status}
                    </span>
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {(agents.data?.agents.length ?? 0) > 0 && (
              <Command.Group heading="AI Agents" className={GROUP}>
                {agents.data!.agents.map((a) => (
                  <Command.Item
                    key={a.id}
                    value={`agent ${a.name} ${a.description ?? ""}`}
                    onSelect={() => go(`/agents/${a.id}`)}
                    className={ITEM}
                  >
                    <Bot className="size-4 shrink-0 text-muted-foreground" />
                    <span className="flex-1 truncate">{a.name}</span>
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {(templates.data?.templates.length ?? 0) > 0 && (
              <Command.Group heading="Templates" className={GROUP}>
                {templates.data!.templates.map((t) => (
                  <Command.Item
                    key={t.slug}
                    value={`template ${t.name} ${t.tags.join(" ")}`}
                    onSelect={() => go("/templates")}
                    className={ITEM}
                  >
                    <PanelsTopLeft className="size-4 shrink-0 text-muted-foreground" />
                    <span className="flex-1 truncate">{t.name}</span>
                    <span className="shrink-0 text-[10px] text-muted-foreground">
                      {t.category}
                    </span>
                  </Command.Item>
                ))}
              </Command.Group>
            )}
          </Command.List>
        </Command>
      </DialogContent>
    </Dialog>
  );
}

const GROUP =
  "px-1 py-1 [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-[10px] [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider [&_[cmdk-group-heading]]:text-muted-foreground dark:[&_[cmdk-group-heading]]:text-white/40";

const ITEM =
  "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 text-[13px] text-muted-foreground outline-none transition-colors duration-200 data-[selected=true]:bg-muted data-[selected=true]:text-foreground dark:text-white/60 dark:data-[selected=true]:bg-white/[0.06] dark:data-[selected=true]:text-white/90";
