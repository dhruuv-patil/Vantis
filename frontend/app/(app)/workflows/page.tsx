"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  ArrowUpRight,
  ChevronDown,
  Loader2,
  MoreHorizontal,
  Plus,
  Search,
  Trash2,
  Workflow,
} from "lucide-react";

import { deleteWorkflow, fetchWorkflows } from "@/lib/api";
import { formatRelativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { CreateWorkflowDialog } from "@/components/app/create-workflow-dialog";

import { Skeleton } from "@/components/ui/skeleton";
import {
  CardGridSkeleton,
  EmptyState,
  ErrorState,
  KpiStrip,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  StatusBadge,
  Toolbar,
} from "@/components/app/page-primitives";

import {
  Reveal,
  StaggerGroup,
  StaggerItem,
  HoverLift,
} from "@/components/motion/motion-primitives";

type StatusFilter = "ALL" | "DRAFT" | "PUBLISHED";
type SortKey = "UPDATED" | "NAME";

type WorkflowItem = {
  id: string;
  name: string;
  description?: string | null;
  status: string;
  updatedAt?: string | null;
};

export default function WorkflowsPage() {
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<StatusFilter>("ALL");
  const [sort, setSort] = useState<SortKey>("UPDATED");
  const [pageSize, setPageSize] = useState(24);
  const [createOpen, setCreateOpen] = useState(false);
  const [toDelete, setToDelete] = useState<WorkflowItem | null>(null);

  const workflows = useQuery({
    queryKey: ["workflows"],
    queryFn: () => fetchWorkflows(),
  });

  const allWorkflows = useMemo(
    () => workflows.data?.workflows ?? [],
    [workflows.data?.workflows],
  );

  const removeMutation = useMutation({
    mutationFn: (id: string) => deleteWorkflow(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["workflows"] });
      setToDelete(null);
    },
  });

  const publishedCount = allWorkflows.filter(
    workflow => workflow.status === "PUBLISHED",
  ).length;

  const draftCount = allWorkflows.filter(
    workflow => workflow.status === "DRAFT",
  ).length;

  const counts: Record<StatusFilter, number> = {
    ALL: allWorkflows.length,
    DRAFT: draftCount,
    PUBLISHED: publishedCount,
  };

  const lastUpdatedAt = useMemo(() => {
    if (!allWorkflows.length) {
      return null;
    }

    const timestamps = allWorkflows
      .map(workflow =>
        workflow.updatedAt ? Date.parse(workflow.updatedAt) : 0,
      )
      .filter(Boolean);

    if (!timestamps.length) {
      return null;
    }

    return new Date(Math.max(...timestamps)).toISOString();
  }, [allWorkflows]);

  const items = useMemo(() => {
    return allWorkflows
      .filter(workflow => {
        if (filter === "ALL") {
          return true;
        }

        return workflow.status === filter;
      })
      .filter(workflow => {
        if (!search.trim()) {
          return true;
        }

        const query = search.toLowerCase();

        return (
          workflow.name.toLowerCase().includes(query) ||
          (workflow.description?.toLowerCase().includes(query) ?? false)
        );
      })
      .sort((a, b) =>
        sort === "NAME"
          ? a.name.localeCompare(b.name)
          : (Date.parse(b.updatedAt ?? "") || 0) -
            (Date.parse(a.updatedAt ?? "") || 0),
      );
  }, [allWorkflows, filter, search, sort]);

  const hasFilters = search.trim().length > 0 || filter !== "ALL";

  const clearFilters = () => {
    setSearch("");
    setFilter("ALL");
  };

  return (
    <PageContainer variant="wide">
      {/* ================================================================
          HEADER (Templates benchmark style)
      ================================================================ */}
      <Reveal>
        <PageHeader
          eyebrow="Workflow Control Plane"
          title="Workflows"
          description="Build, version, execute and monitor your automation workflows from one place."
          actions={
            <Button
              onClick={() => setCreateOpen(true)}
              className="gap-2"
            >
              <Plus className="size-4" />
              <span>New workflow</span>
            </Button>
          }
        />
      </Reveal>

      {/* ================================================================
          KPI STRIP
      ================================================================ */}
      <Reveal delay={0.04} distance={8}>
        <KpiStrip
          items={[
            {
              label: "Total workflows",
              value: workflows.isLoading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                allWorkflows.length.toLocaleString()
              ),
              detail: workflows.isLoading
                ? "Loading"
                : allWorkflows.length === 0
                  ? "No workflows yet"
                  : "In this workspace",
              tone: "neutral",
            },
            {
              label: "Published",
              value: workflows.isLoading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                publishedCount.toLocaleString()
              ),
              detail: workflows.isLoading
                ? "Loading"
                : allWorkflows.length === 0
                  ? "No workflows yet"
                  : "Live and runnable",
              tone: allWorkflows.length === 0 || publishedCount === 0 ? "neutral" : "success",
            },
            {
              label: "Drafts",
              value: workflows.isLoading ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                draftCount.toLocaleString()
              ),
              detail: workflows.isLoading
                ? "Loading"
                : allWorkflows.length === 0
                  ? "No workflows yet"
                  : draftCount > 0
                    ? "Not yet published"
                    : "No drafts",
              tone: draftCount > 0 ? "warning" : "neutral",
            },
            {
              label: "Last updated",
              value: workflows.isLoading ? (
                <Skeleton className="h-7 w-20" />
              ) : !lastUpdatedAt ? (
                "—"
              ) : (
                formatRelativeTime(lastUpdatedAt)
              ),
              detail: workflows.isLoading
                ? "Loading"
                : !lastUpdatedAt
                  ? "No updates yet"
                  : "Most recent change",
              tone: "neutral",
            },
          ]}
        />
      </Reveal>

      {/* ================================================================
          SEARCH / FILTER / SORT TOOLBAR
      ================================================================ */}
      <Reveal delay={0.08} distance={8}>
        <div className="mt-6">
          <Toolbar>
            <div className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground/60 dark:text-white/40" />

              <Input
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder="Search workflows..."
                className="pl-10 text-sm"
              />

              {search && (
                <button
                  type="button"
                  onClick={() => setSearch("")}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[10px] text-muted-foreground/60 hover:text-foreground dark:text-white/40 dark:hover:text-white"
                >
                  Clear
                </button>
              )}
            </div>

            <div className="flex h-10 shrink-0 items-center gap-1 rounded-lg border border-border dark:border-white/[0.08] bg-muted/40 dark:bg-[#0a0a0c] p-1">
              {(["ALL", "DRAFT", "PUBLISHED"] as const).map(status => {
                const active = filter === status;

                return (
                  <button
                    key={status}
                    type="button"
                    onClick={() => setFilter(status)}
                    className={cn(
                      "flex h-8 items-center gap-1.5 rounded-md px-3 text-xs font-medium transition-colors",
                      active
                        ? "bg-background text-foreground font-semibold shadow-sm dark:bg-white dark:text-black"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground dark:text-white/50 dark:hover:bg-white/[0.04] dark:hover:text-white/80",
                    )}
                  >
                    <span>
                      {status === "ALL"
                        ? "All"
                        : status === "DRAFT"
                          ? "Drafts"
                          : "Published"}
                    </span>

                    <span
                      className={cn(
                        "text-[11px] tabular-nums",
                        active ? "text-foreground/80 dark:text-black/70" : "text-muted-foreground dark:text-white/45",
                      )}
                    >
                      {counts[status]}
                    </span>
                  </button>
                );
              })}
            </div>

            <div className="relative shrink-0">
              <select
                value={sort}
                onChange={event => setSort(event.target.value as SortKey)}
                aria-label="Sort workflows"
                className="h-10 appearance-none rounded-lg border border-border dark:border-white/[0.08] bg-background dark:bg-[#0a0a0c] pl-3 pr-9 text-xs font-medium text-foreground/80 dark:text-white/70 outline-none transition-colors hover:text-foreground dark:hover:text-white focus:border-foreground/30 dark:focus:border-white/20"
              >
                <option value="UPDATED">Recently updated</option>
                <option value="NAME">Name (A–Z)</option>
              </select>

              <ChevronDown className="pointer-events-none absolute right-3 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground/60 dark:text-white/40" />
            </div>
          </Toolbar>
        </div>
      </Reveal>

      {/* ================================================================
          RESULT COUNT
      ================================================================ */}
      {!workflows.isLoading &&
        !workflows.isError &&
        allWorkflows.length > 0 && (
          <div className="mt-4 flex items-center justify-between px-0.5">
            <span className="text-[11px] text-muted-foreground dark:text-white/45">
              Showing {items.length} of {allWorkflows.length} workflows
            </span>

            {hasFilters && (
              <button
                type="button"
                onClick={clearFilters}
                className="text-[11px] text-muted-foreground hover:text-foreground dark:text-white/45 dark:hover:text-white"
              >
                Clear filters
              </button>
            )}
          </div>
        )}

      {/* ================================================================
          CONTENT
      ================================================================ */}
      <div className="mt-4">
        {workflows.isLoading ? (
          <CardGridSkeleton count={6} />
        ) : workflows.isError ? (
          <ErrorState
            title="Unable to load workflows"
            message="Check your connection and try again."
            onRetry={() => workflows.refetch()}
          />
        ) : items.length === 0 ? (
          <EmptyState
            icon={Workflow}
            title={hasFilters ? "No workflows found" : "No workflows yet"}
            description={
              hasFilters
                ? "Try changing your search or status filter."
                : "Create your first workflow or start from a proven template."
            }
            actions={
              hasFilters ? (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={clearFilters}
                  className="gap-1.5"
                >
                  Clear filters
                </Button>
              ) : (
                <Link href="/templates">
                  <Button size="sm" variant="outline" className="gap-1.5">
                    Browse templates
                  </Button>
                </Link>
              )
            }
          />
        ) : (
          <>
            <StaggerGroup key={`${filter}:${search}:${sort}:${pageSize}`} stagger={0.035}>
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {items.slice(0, pageSize).map(workflow => (
                  <StaggerItem key={workflow.id} distance={8}>
                    <HoverLift scale={1.004} y={-2}>
                      <WorkflowCard
                        workflow={workflow}
                        onDelete={() => {
                          removeMutation.reset();
                          setToDelete(workflow);
                        }}
                      />
                    </HoverLift>
                  </StaggerItem>
                ))}
              </div>
            </StaggerGroup>

            <LoadMoreButton
              loadedCount={Math.min(pageSize, items.length)}
              totalCount={items.length}
              onLoadMore={() => setPageSize(prev => prev + 24)}
            />
          </>
        )}
      </div>

      <CreateWorkflowDialog open={createOpen} onOpenChange={setCreateOpen} />

      {/* ================================================================
          DELETE CONFIRMATION
      ================================================================ */}
      <AlertDialog
        open={!!toDelete}
        onOpenChange={open => {
          if (!open && !removeMutation.isPending) {
            setToDelete(null);
          }
        }}
      >
        <AlertDialogContent className="border-border bg-card dark:border-white/[0.08] dark:bg-[#0a0a0c] text-foreground dark:text-white sm:max-w-md">
          <AlertDialogHeader>
            <AlertDialogTitle className="text-base font-semibold tracking-tight">
              Delete this workflow?
            </AlertDialogTitle>

            <AlertDialogDescription className="text-sm leading-6 text-muted-foreground dark:text-white/50">
              <span className="font-medium text-foreground dark:text-white/90">
                {toDelete?.name}
              </span>{" "}
              will be permanently removed. This can&apos;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>

          {removeMutation.isError && (
            <p
              role="alert"
              className="rounded-lg border border-red-500/20 bg-red-500/[0.06] px-3 py-2 text-xs text-red-600 dark:text-red-300"
            >
              Couldn&apos;t delete the workflow. Try again.
            </p>
          )}

          <AlertDialogFooter className="gap-2 sm:gap-2">
            <AlertDialogCancel
              disabled={removeMutation.isPending}
              className="mt-0"
            >
              Cancel
            </AlertDialogCancel>

            <AlertDialogAction
              disabled={removeMutation.isPending}
              onClick={event => {
                event.preventDefault();
                if (toDelete) {
                  removeMutation.mutate(toDelete.id);
                }
              }}
              className="gap-2 border border-red-500/25 bg-red-500/10 text-red-600 hover:bg-red-500/20 dark:bg-red-500/20 dark:text-red-300 dark:hover:bg-red-500/30 dark:hover:text-red-200"
            >
              {removeMutation.isPending && (
                <Loader2 className="size-4 animate-spin" />
              )}
              Delete workflow
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </PageContainer>
  );
}

/* ==========================================================================
   WORKFLOW CARD
============================================================================ */

function WorkflowCard({
  workflow,
  onDelete,
}: {
  workflow: WorkflowItem;
  onDelete: () => void;
}) {
  const published = workflow.status === "PUBLISHED";

  return (
    <article
      className={cn(
        "group relative flex h-full min-h-[176px] flex-col overflow-hidden rounded-xl",
        "border border-border dark:border-white/[0.065] bg-card dark:bg-[#0a0a0c]",
        "transition-[border-color,background-color,box-shadow] duration-200",
        "hover:border-foreground/20 dark:hover:border-white/[0.12] hover:bg-muted/30 dark:hover:bg-[#111114]",
        "hover:shadow-[0_18px_45px_rgba(0,0,0,0.06)] dark:hover:shadow-[0_18px_45px_rgba(0,0,0,0.3)]",
        "focus-within:border-foreground/30 dark:focus-within:border-white/20",
      )}
    >
      {/* Whole card is clickable; the menu sits above this link */}
      <Link
        href={`/workflows/${workflow.id}`}
        aria-label={`Open ${workflow.name}`}
        className="absolute inset-0 z-0 rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground/30 dark:focus-visible:ring-white/30"
      />

      {/* Top: status, name, description */}
      <div className="pointer-events-none relative z-[1] flex flex-1 flex-col p-5 pb-4">
        <div className="flex h-7 items-center">
          <StatusBadge
            status={workflow.status}
            label={published ? "Published" : "Draft"}
          />
        </div>

        <h3
          title={workflow.name}
          className="mt-3 truncate text-base font-medium tracking-tight text-foreground/90 group-hover:text-foreground dark:text-white/90 dark:group-hover:text-white"
        >
          {workflow.name}
        </h3>

        <p
          title={workflow.description ?? undefined}
          className={cn(
            "mt-1.5 line-clamp-2 text-xs leading-relaxed",
            workflow.description ? "text-muted-foreground dark:text-white/60" : "text-muted-foreground/70 dark:text-white/45",
          )}
        >
          {workflow.description || "No description added."}
        </p>
      </div>

      {/* Footer */}
      <div className="pointer-events-none relative z-[1] flex items-center justify-between gap-3 border-t border-border dark:border-white/[0.065] bg-muted/20 dark:bg-white/[0.015] px-5 py-3">
        <span
          className="truncate text-[11px] text-muted-foreground dark:text-white/40"
          title={workflow.updatedAt ?? undefined}
        >
          {workflow.updatedAt
            ? `Updated ${formatRelativeTime(workflow.updatedAt)}`
            : "No recent update"}
        </span>

        <span className="flex shrink-0 items-center gap-1 text-[11px] font-medium text-muted-foreground group-hover:text-foreground dark:text-white/45 dark:group-hover:text-white transition-colors">
          Open
          <ArrowUpRight className="size-3 transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5" />
        </span>
      </div>

      {/* Actions */}
      <div className="absolute right-3.5 top-4 z-10">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label={`Actions for ${workflow.name}`}
              className="flex size-7 items-center justify-center rounded-md border border-transparent text-muted-foreground/60 transition-colors hover:border-border hover:bg-muted hover:text-foreground dark:text-white/40 dark:hover:border-white/[0.09] dark:hover:bg-white/[0.06] dark:hover:text-white focus-visible:border-foreground/30 dark:focus-visible:border-white/25 focus-visible:outline-none data-[state=open]:border-border dark:data-[state=open]:border-white/[0.09] data-[state=open]:bg-muted dark:data-[state=open]:bg-white/[0.06] data-[state=open]:text-foreground dark:data-[state=open]:text-white"
            >
              <MoreHorizontal className="size-4" />
            </button>
          </DropdownMenuTrigger>

          <DropdownMenuContent
            align="end"
            className="w-44 border-border bg-popover text-popover-foreground dark:border-white/[0.08] dark:bg-[#0a0a0c] dark:text-white"
          >
            <DropdownMenuItem
              asChild
              className="cursor-pointer text-foreground/80 focus:bg-muted focus:text-foreground dark:text-white/80 dark:focus:bg-white/[0.06] dark:focus:text-white"
            >
              <Link href={`/workflows/${workflow.id}`}>
                <ArrowUpRight className="mr-2 size-4 text-muted-foreground dark:text-white/45" />
                Open workflow
              </Link>
            </DropdownMenuItem>

            <DropdownMenuSeparator className="bg-border dark:bg-white/[0.07]" />

            <DropdownMenuItem
              onSelect={onDelete}
              className="cursor-pointer text-red-600 focus:bg-red-500/10 focus:text-red-700 dark:text-red-300 dark:focus:text-red-200"
            >
              <Trash2 className="mr-2 size-4" />
              Delete
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </article>
  );
}