"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import {
  Activity,
  AlertTriangle,
  Bot,
  ChevronLeft,
  FileText,
  Home,
  LayoutDashboard,
  CreditCard,
  LogOut,
  Menu,
  PanelsTopLeft,
  Plug,
  Search,
  Settings,
  Users,
  Workflow,
  X,
} from "lucide-react";
import { toast } from "sonner";

import { getErrorMessage, logout } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { cn, initialsFrom } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { LogoMark } from "@/components/brand/logo";
import { OrgSwitcher } from "@/components/app/org-switcher";
import { NotificationsMenu } from "@/components/app/notifications-menu";
import { CommandPalette } from "@/components/app/command-palette";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { HoverLift } from "@/components/motion/motion-primitives";
import { PageTransition } from "@/components/motion/page-transition";

interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const NAV: NavGroup[] = [
  {
    label: "Workspace",
    items: [
      {
        label: "Home",
        href: "/home",
        icon: Home,
      },
      {
        label: "Dashboard",
        href: "/dashboard",
        icon: LayoutDashboard,
      },
      {
        label: "Workflows",
        href: "/workflows",
        icon: Workflow,
      },
      {
        label: "Executions",
        href: "/executions",
        icon: Activity,
      },
      {
        label: "Templates",
        href: "/templates",
        icon: PanelsTopLeft,
      },
    ],
  },
  {
    label: "Reliability",
    items: [
      {
        label: "Anomalies",
        href: "/reliability",
        icon: AlertTriangle,
      },
    ],
  },
  {
    label: "Automation",
    items: [
      {
        label: "AI Agents",
        href: "/agents",
        icon: Bot,
      },
      {
        label: "Integrations",
        href: "/integrations",
        icon: Plug,
      },
      {
        label: "Logs",
        href: "/logs",
        icon: FileText,
      },
    ],
  },
  {
    label: "Organization",
    items: [
      {
        label: "Team",
        href: "/team",
        icon: Users,
      },
      {
        label: "Billing",
        href: "/billing",
        icon: CreditCard,
      },
      {
        label: "Settings",
        href: "/settings",
        icon: Settings,
      },
    ],
  },
];

function getPageTitle(pathname: string): { title: string; section?: string } {
  if (pathname === "/home") return { title: "Home" };
  if (pathname === "/dashboard") return { title: "Dashboard" };
  if (pathname === "/workflows") return { title: "Workflows" };
  if (pathname.startsWith("/workflows/")) return { title: "Editor", section: "Workflows" };
  if (pathname === "/executions") return { title: "Executions" };
  if (pathname.startsWith("/executions/")) return { title: "Run", section: "Executions" };
  if (pathname === "/templates") return { title: "Templates" };
  if (pathname === "/reliability") return { title: "Anomalies", section: "Reliability" };
  if (pathname.startsWith("/reliability/")) return { title: "Detail", section: "Reliability" };
  if (pathname === "/agents") return { title: "AI Agents" };
  if (pathname.startsWith("/agents/")) return { title: "Agent", section: "AI Agents" };
  if (pathname === "/integrations") return { title: "Integrations" };
  if (pathname === "/logs") return { title: "Logs" };
  if (pathname === "/team") return { title: "Team" };
  if (pathname === "/billing") return { title: "Billing & Usage", section: "Organization" };
  if (pathname === "/settings") return { title: "Settings" };
  return { title: "Vantis" };
}

function getSectionHref(section?: string): string {
  switch (section) {
    case "Workflows":
      return "/workflows";
    case "Executions":
      return "/executions";
    case "Reliability":
      return "/reliability";
    case "AI Agents":
      return "/agents";
    default:
      return "/dashboard";
  }
}

export function AppShell({
  defaultCollapsed = false,
  children,
}: {
  defaultCollapsed?: boolean;
  children: React.ReactNode;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const pathname = usePathname();

  // Hydrate collapsed state from localStorage (SSR-safe) and sync cookie
  useEffect(() => {
    try {
      const saved =
        localStorage.getItem("vantis:sidebar-collapsed") ??
        localStorage.getItem("oblayer:sidebar-collapsed") ??
        localStorage.getItem("tracerun:sidebar-collapsed");
      if (saved !== null) {
        const isCollapsed = saved === "true";
        setCollapsed(isCollapsed);
        document.cookie = `vantis_sidebar_collapsed=${isCollapsed}; path=/; max-age=31536000; SameSite=Lax`;
      }
    } catch {
      // ignore in environments without localStorage
    }
  }, []);

  // Close mobile drawer on route change
  useEffect(() => {
    setMobileOpen(false);
  }, [pathname]);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem("vantis:sidebar-collapsed", String(next));
        document.cookie = `vantis_sidebar_collapsed=${next}; path=/; max-age=31536000; SameSite=Lax`;
      } catch {
        // ignore
      }
      return next;
    });
  };

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (
        (e.metaKey || e.ctrlKey) &&
        e.key.toLowerCase() === "k"
      ) {
        const el = document.activeElement;

        const typing =
          el instanceof HTMLElement &&
          (el.tagName === "INPUT" ||
            el.tagName === "TEXTAREA" ||
            el.isContentEditable);

        if (typing) return;

        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    }

    document.addEventListener("keydown", onKey);

    return () =>
      document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* Desktop Sidebar */}
      <Sidebar
        collapsed={collapsed}
        onToggle={toggleCollapsed}
      />

      {/* Mobile Drawer (screens < lg) */}
      <MobileDrawer
        open={mobileOpen}
        onOpenChange={setMobileOpen}
      />

      <div className="flex min-w-0 flex-1 flex-col h-screen overflow-hidden">
        <Topbar
          onOpenPalette={() => setPaletteOpen(true)}
          onOpenMobile={() => setMobileOpen(true)}
        />

        <main className="flex-1 min-h-0 overflow-y-auto">
          <PageTransition className="min-h-full flex flex-col">{children}</PageTransition>
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
      />
    </div>
  );
}

function Sidebar({
  collapsed,
  onToggle,
}: {
  collapsed: boolean;
  onToggle: () => void;
}) {
  const pathname = usePathname();

  return (
    <aside
      className={cn(
        "sticky top-0 hidden h-screen shrink-0 flex-col border-r border-border bg-card/95 backdrop-blur-md transition-[width] duration-200 lg:flex dark:border-white/[0.075] dark:bg-[#0a0a0c]",
        collapsed ? "w-[68px]" : "w-60"
      )}
    >
      {/* =====================================================
          BRAND
          Expanded: logo + toggle on same row
          Collapsed: logo, then toggle underneath
         ===================================================== */}
      <div className="shrink-0 border-b border-border dark:border-white/[0.075]">
        <div
          className={cn(
            "flex items-center",
            collapsed
              ? "h-12 justify-center px-3"
              : "h-12 justify-between px-3"
          )}
        >
          <Link
            href="/dashboard"
            className="flex min-w-0 items-center gap-2 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 rounded"
          >
            <LogoMark className="!h-6 !w-6 shrink-0" />

            {!collapsed && (
              <span className="truncate text-sm font-semibold tracking-tight text-foreground/90 dark:text-white/90">
                Vantis
              </span>
            )}
          </Link>

          {/* Expanded: toggle beside logo */}
          {!collapsed && (
            <Button
              variant="ghost"
              size="icon"
              onClick={onToggle}
              aria-label="Collapse sidebar"
              className={cn(
                "!h-7 !w-7 shrink-0 rounded-lg",
                "border border-border dark:border-white/[0.08]",
                "bg-muted/30 dark:bg-white/[0.025]",
                "text-muted-foreground dark:text-white/40",
                "hover:bg-muted hover:text-foreground dark:hover:bg-white/[0.07] dark:hover:text-white/80",
                "focus-visible:ring-1 focus-visible:ring-primary/20 dark:focus-visible:ring-white/20"
              )}
            >
              <ChevronLeft className="size-3.5" />
            </Button>
          )}
        </div>

        {/* Collapsed: toggle directly underneath logo */}
        {collapsed && (
          <div className="flex h-9 items-center justify-center pb-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={onToggle}
              aria-label="Expand sidebar"
              className={cn(
                "!h-7 !w-7 rounded-lg",
                "border border-border dark:border-white/[0.08]",
                "bg-muted/30 dark:bg-white/[0.025]",
                "text-muted-foreground dark:text-white/40",
                "hover:bg-muted hover:text-foreground dark:hover:bg-white/[0.07] dark:hover:text-white/80",
                "focus-visible:ring-1 focus-visible:ring-primary/20 dark:focus-visible:ring-white/20"
              )}
            >
              <ChevronLeft className="size-3.5 rotate-180" />
            </Button>
          </div>
        )}
      </div>

      {/* =====================================================
          ORGANIZATION
          Keep this visible only in expanded mode.
         ===================================================== */}
      {!collapsed && (
        <div className="shrink-0 border-b border-border dark:border-white/[0.075] px-3 py-2.5">
          <OrgSwitcher collapsed={false} />
        </div>
      )}

      {/* =====================================================
          NAVIGATION
         ===================================================== */}
      <TooltipProvider delayDuration={150}>
        <nav className="flex-1 space-y-5 overflow-y-auto px-2.5 py-3">
          {NAV.map((group) => (
            <div key={group.label}>
              {!collapsed && (
                <p className="px-2.5 pb-1.5 mono-eyebrow">
                  {group.label}
                </p>
              )}

              <ul className="space-y-0.5">
                {group.items.map((item) => {
                  const active =
                    pathname === item.href ||
                    (item.href !== "/home" &&
                      pathname.startsWith(`${item.href}/`));

                  const Icon = item.icon;

                  const inner = (
                    <>
                      <Icon className="size-4 shrink-0 opacity-60" />

                      {!collapsed && (
                        <span className="flex-1 truncate text-[13px]">
                          {item.label}
                        </span>
                      )}
                    </>
                  );

                  const linkNode = (
                    <Link
                      href={item.href}
                      aria-current={active ? "page" : undefined}
                      className={cn(
                        "relative flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 dark:focus-visible:ring-white/20",
                        collapsed && "justify-center px-2.5",
                        active
                          ? "bg-muted text-foreground before:absolute before:left-0 before:top-1/2 before:h-5 before:w-[2px] before:-translate-y-1/2 before:rounded-r before:bg-foreground dark:bg-white/[0.06] dark:text-white/90 dark:before:bg-white/80"
                          : "text-muted-foreground hover:bg-muted/50 hover:text-foreground dark:text-white/45 dark:hover:bg-white/[0.04] dark:hover:text-white/80"
                      )}
                    >
                      {inner}
                    </Link>
                  );

                  return (
                    <li key={item.href}>
                      <HoverLift scale={1.01} y={-1}>
                        {collapsed ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              {linkNode}
                            </TooltipTrigger>
                            <TooltipContent side="right" className="font-medium">
                              {item.label}
                            </TooltipContent>
                          </Tooltip>
                        ) : (
                          linkNode
                        )}
                      </HoverLift>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </nav>
      </TooltipProvider>

      {/* =====================================================
          USER
         ===================================================== */}
      <UserBlock collapsed={collapsed} />
    </aside>
  );
}

function MobileDrawer({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const pathname = usePathname();

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm transition-opacity duration-200 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          className="fixed inset-y-0 left-0 z-50 flex w-72 max-w-[85vw] flex-col border-r border-border bg-card text-card-foreground p-0 shadow-2xl transition-transform duration-200 ease-out focus:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left dark:border-white/[0.08] dark:bg-[#0a0a0c]"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="sr-only">
            Navigation Menu
          </DialogPrimitive.Title>

          {/* Header */}
          <div className="flex h-12 shrink-0 items-center justify-between border-b border-border px-4 dark:border-white/[0.075]">
            <Link
              href="/dashboard"
              onClick={() => onOpenChange(false)}
              className="flex items-center gap-2.5"
            >
              <LogoMark className="!h-6 !w-6 shrink-0" />
              <span className="text-sm font-semibold tracking-tight text-foreground dark:text-white/90">
                Vantis
              </span>
            </Link>

            <div className="flex items-center gap-1.5">
              <ThemeToggle />

              <DialogPrimitive.Close asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-7 rounded-lg border border-border bg-background/50 text-muted-foreground hover:bg-muted hover:text-foreground dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/50 dark:hover:bg-white/[0.06] dark:hover:text-white"
                  aria-label="Close navigation"
                >
                  <X className="size-3.5" />
                </Button>
              </DialogPrimitive.Close>
            </div>
          </div>

          {/* Org Switcher */}
          <div className="shrink-0 border-b border-border px-3 py-2.5 dark:border-white/[0.075]">
            <OrgSwitcher collapsed={false} />
          </div>

          {/* Navigation */}
          <nav className="flex-1 space-y-5 overflow-y-auto px-2.5 py-3">
            {NAV.map((group) => (
              <div key={group.label}>
                <p className="px-2.5 pb-1.5 mono-eyebrow">{group.label}</p>
                <ul className="space-y-0.5">
                  {group.items.map((item) => {
                    const active =
                      pathname === item.href ||
                      (item.href !== "/home" &&
                        pathname.startsWith(`${item.href}/`));

                    const Icon = item.icon;

                    return (
                      <li key={item.href}>
                        <Link
                          href={item.href}
                          onClick={() => onOpenChange(false)}
                          aria-current={active ? "page" : undefined}
                          className={cn(
                            "relative flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 dark:focus-visible:ring-white/20",
                            active
                              ? "bg-muted text-foreground before:absolute before:left-0 before:top-1/2 before:h-5 before:w-[2px] before:-translate-y-1/2 before:rounded-r before:bg-foreground dark:bg-white/[0.06] dark:text-white/90 dark:before:bg-white/80"
                              : "text-muted-foreground hover:bg-muted/50 hover:text-foreground dark:text-white/45 dark:hover:bg-white/[0.04] dark:hover:text-white/80"
                          )}
                        >
                          <Icon className="size-4 shrink-0 opacity-60" />
                          <span className="flex-1 truncate text-[13px]">
                            {item.label}
                          </span>
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </nav>

          {/* User Block */}
          <UserBlock
            collapsed={false}
            onNavigate={() => onOpenChange(false)}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

function Topbar({
  onOpenPalette,
  onOpenMobile,
}: {
  onOpenPalette: () => void;
  onOpenMobile: () => void;
}) {
  const pathname = usePathname();
  const { title, section } = getPageTitle(pathname);

  return (
    <header className="sticky top-0 z-30 flex h-12 shrink-0 items-center justify-between gap-3 border-b border-border bg-background/80 px-4 backdrop-blur-md lg:px-6 dark:border-white/[0.075] dark:bg-black/80">
      {/* Left: Mobile hamburger + breadcrumb / page title */}
      <div className="flex items-center gap-2.5 min-w-0">
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onOpenMobile}
          aria-label="Open navigation menu"
          className="size-8 shrink-0 rounded-lg border border-border bg-background/50 text-muted-foreground hover:bg-muted hover:text-foreground lg:hidden focus-visible:ring-1 focus-visible:ring-primary/20 dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/50 dark:hover:bg-white/[0.06] dark:hover:text-white"
        >
          <Menu className="size-4" />
        </Button>

        {/* Desktop & Mobile breadcrumb / title */}
        <div className="flex items-center gap-1.5 truncate text-[13px]">
          {section ? (
            <>
              <Link
                href={getSectionHref(section)}
                className="text-muted-foreground transition-colors hover:text-foreground hover:underline dark:text-white/45 dark:hover:text-white/80"
              >
                {section}
              </Link>
              <span className="text-muted-foreground/60 dark:text-white/30" aria-hidden="true">
                /
              </span>
              <span className="font-medium text-foreground truncate dark:text-white/90">
                {title}
              </span>
            </>
          ) : (
            <span className="font-medium text-foreground truncate dark:text-white/90">
              {title}
            </span>
          )}
        </div>
      </div>

      {/* Right / Search & Notifications & Theme */}
      <div className="flex items-center gap-2">
        {/* Desktop full search button */}
        <button
          type="button"
          onClick={onOpenPalette}
          className="hidden lg:flex w-72 items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-1.5 text-[13px] text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 dark:border-white/[0.08] dark:bg-white/[0.03] dark:text-white/40 dark:hover:bg-white/[0.06] dark:hover:text-white/70"
          title="Search and commands"
        >
          <Search className="size-3.5 shrink-0" />
          <span className="truncate">Search or jump to…</span>
          <kbd className="ml-auto rounded-md border border-border bg-background/80 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground dark:border-white/[0.10] dark:bg-white/[0.04] dark:text-white/35">
            ⌘K
          </kbd>
        </button>

        {/* Mobile search button */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onOpenPalette}
          aria-label="Search and commands (⌘K)"
          className="flex lg:hidden size-8 rounded-lg border border-border bg-background/50 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-1 focus-visible:ring-primary/20 dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/50 dark:hover:bg-white/[0.06] dark:hover:text-white"
        >
          <Search className="size-4" />
        </Button>

        <ThemeToggle />

        <NotificationsMenu />
      </div>
    </header>
  );
}

function UserBlock({
  collapsed,
  onNavigate,
}: {
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const router = useRouter();
  const user = useAuthStore((s) => s.user);
  const role = useAuthStore((s) => s.currentRole);

  if (!user) return null;

  async function onLogout() {
    try {
      await logout();
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      router.replace("/login");
    }
  }

  return (
    <div className="border-t border-border p-2.5 dark:border-white/[0.075]">
      <DropdownMenu>
        <DropdownMenuTrigger
          className={cn(
            "flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-[13px] transition-colors duration-150 hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary/20 dark:hover:bg-white/[0.04]",
            collapsed && "justify-center px-0"
          )}
        >
          <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-muted font-semibold text-[10px] text-foreground dark:bg-white/[0.06] dark:text-white/70">
            {initialsFrom(user.fullName)}
          </span>

          {!collapsed && (
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium text-foreground dark:text-white/80">
                {user.fullName}
              </span>

              <span className="block truncate text-[11px] text-muted-foreground dark:text-white/40">
                {role}
              </span>
            </span>
          )}
        </DropdownMenuTrigger>

        <DropdownMenuContent
          align={collapsed ? "center" : "end"}
          side="top"
          className="w-56 border-border bg-popover text-popover-foreground shadow-xl dark:border-white/[0.08] dark:bg-[#0a0a0c]"
        >
          <DropdownMenuLabel className="font-normal">
            <span className="block truncate font-medium text-foreground dark:text-white/80">
              {user.fullName}
            </span>

            <span className="block truncate text-xs text-muted-foreground dark:text-white/40">
              {user.email}
            </span>
          </DropdownMenuLabel>

          <DropdownMenuSeparator />

          <DropdownMenuItem
            onSelect={() => {
              onNavigate?.();
              router.push("/settings");
            }}
            className="gap-2 cursor-pointer text-foreground/80 focus:text-foreground dark:text-white/70 dark:focus:text-white"
          >
            <Settings className="size-4 text-muted-foreground dark:text-white/50" />
            Settings
          </DropdownMenuItem>

          <DropdownMenuItem
            onSelect={() => {
              onNavigate?.();
              router.push("/team");
            }}
            className="gap-2 cursor-pointer text-foreground/80 focus:text-foreground dark:text-white/70 dark:focus:text-white"
          >
            <Users className="size-4 text-muted-foreground dark:text-white/50" />
            Team & Members
          </DropdownMenuItem>

          <DropdownMenuSeparator />

          <DropdownMenuItem
            onSelect={(e) => {
              e.preventDefault();
              void onLogout();
            }}
            className="gap-2 cursor-pointer text-red-400 focus:text-red-400"
          >
            <LogOut className="size-4" />
            Log out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}