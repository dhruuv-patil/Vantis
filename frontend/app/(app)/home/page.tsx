"use client";

import { useCallback, useState } from "react";

import Link from "next/link";

import { useRouter } from "next/navigation";

import { useQuery } from "@tanstack/react-query";

import {
  ArrowRight,
  Bot,
  LayoutTemplate,
  Plus,
  Sparkles,
} from "lucide-react";

import {
  createWorkflow,
  fetchWorkflows,
  generateWorkflow,
  saveWorkflowGraph,
} from "@/lib/api";

import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";

import { Skeleton } from "@/components/ui/skeleton";

/* ==========================================================================
   AI GRAPH LAYOUT
   ========================================================================== */

/**
 * Vantis uses a left-to-right React Flow canvas:
 *
 *   Trigger → Step → Step → Step
 *
 * AI providers can return nodes with their own positions, often stacked
 * vertically. Before saving an AI-generated graph, normalize its positions
 * into the same horizontal layout used by the Vantis workflow builder.
 */
function layoutGraphHorizontally(graph: any) {
  if (
    !graph ||
    !Array.isArray(graph.nodes) ||
    graph.nodes.length === 0
  ) {
    return graph;
  }

  const nodes = graph.nodes;

  const edges = Array.isArray(graph.edges)
    ? graph.edges
    : [];

  /*
   * These values are intentionally close to the spacing used by the
   * Vantis horizontal builder.
   */
  const NODE_X_GAP = 220;
  const NODE_Y_GAP = 150;

  const nodeIndex = new Map<string, number>();

  const outgoing = new Map<
    string,
    string[]
  >();

  const incomingCount = new Map<
    string,
    number
  >();

  /*
   * Register every node.
   */
  nodes.forEach(
    (node: any, index: number) => {
      const id = String(node.id);

      nodeIndex.set(id, index);

      outgoing.set(id, []);

      incomingCount.set(id, 0);
    },
  );

  /*
   * Build the directed graph from React Flow edges.
   *
   * Invalid edges are ignored defensively so one malformed AI edge
   * cannot break the entire layout.
   */
  edges.forEach((edge: any) => {
    const source = String(
      edge.source ?? "",
    );

    const target = String(
      edge.target ?? "",
    );

    if (
      !nodeIndex.has(source) ||
      !nodeIndex.has(target) ||
      source === target
    ) {
      return;
    }

    outgoing
      .get(source)
      ?.push(target);

    incomingCount.set(
      target,
      (incomingCount.get(target) ?? 0) + 1,
    );
  });

  /*
   * ------------------------------------------------------------------------
   * TOPOLOGICAL HORIZONTAL LAYERS
   * ------------------------------------------------------------------------
   *
   * level 0 = trigger/root
   * level 1 = next workflow step
   * level 2 = next workflow step
   * ...
   *
   * Every level becomes one X column.
   */
  const levels = new Map<
    string,
    number
  >();

  const queue: string[] = [];

  nodes.forEach((node: any) => {
    const id = String(node.id);

    if (
      (incomingCount.get(id) ?? 0) === 0
    ) {
      levels.set(id, 0);

      queue.push(id);
    }
  });

  /*
   * If there is no root, fall back to the original node order.
   */
  if (queue.length === 0) {
    nodes.forEach(
      (node: any, index: number) => {
        levels.set(
          String(node.id),
          index,
        );
      },
    );
  } else {
    let cursor = 0;

    while (cursor < queue.length) {
      const source =
        queue[cursor++];

      const sourceLevel =
        levels.get(source) ?? 0;

      for (const target of
        outgoing.get(source) ?? []) {
        const nextLevel =
          sourceLevel + 1;

        levels.set(
          target,
          Math.max(
            levels.get(target) ?? 0,
            nextLevel,
          ),
        );

        const remaining =
          (incomingCount.get(target) ?? 0) -
          1;

        incomingCount.set(
          target,
          remaining,
        );

        if (remaining === 0) {
          queue.push(target);
        }
      }
    }

    /*
     * Handle cyclic/unreachable nodes defensively.
     */
    const maxLevel =
      Math.max(
        ...Array.from(
          levels.values(),
        ),
        0,
      );

    nodes.forEach(
      (node: any, index: number) => {
        const id = String(node.id);

        if (!levels.has(id)) {
          levels.set(
            id,
            maxLevel + 1 + index,
          );
        }
      },
    );
  }

  /*
   * ------------------------------------------------------------------------
   * GROUP NODES INTO COLUMNS
   * ------------------------------------------------------------------------
   */

  const columns = new Map<
    number,
    Array<{
      node: any;
      index: number;
    }>
  >();

  nodes.forEach(
    (node: any, index: number) => {
      const id = String(node.id);

      const level =
        levels.get(id) ?? 0;

      const column =
        columns.get(level) ?? [];

      column.push({
        node,
        index,
      });

      columns.set(
        level,
        column,
      );
    },
  );

  /*
   * ------------------------------------------------------------------------
   * PRESERVE BRANCH ORDER
   * ------------------------------------------------------------------------
   *
   * If the AI supplied useful Y coordinates, use them only to decide
   * the order of nodes inside a column.
   *
   * The actual X/Y coordinates are replaced below.
   */
  for (const column of
    columns.values()) {
    column.sort((a, b) => {
      const ay =
        typeof a.node?.position?.y ===
        "number"
          ? a.node.position.y
          : Number.MAX_SAFE_INTEGER;

      const by =
        typeof b.node?.position?.y ===
        "number"
          ? b.node.position.y
          : Number.MAX_SAFE_INTEGER;

      if (ay !== by) {
        return ay - by;
      }

      const ax =
        typeof a.node?.position?.x ===
        "number"
          ? a.node.position.x
          : Number.MAX_SAFE_INTEGER;

      const bx =
        typeof b.node?.position?.x ===
        "number"
          ? b.node.position.x
          : Number.MAX_SAFE_INTEGER;

      if (ax !== bx) {
        return ax - bx;
      }

      return a.index - b.index;
    });
  }

  /*
   * ------------------------------------------------------------------------
   * CREATE HORIZONTAL POSITIONS
   * ------------------------------------------------------------------------
   *
   * Example:
   *
   *                    ┌── Slack
   * Webhook → AI ──────┤
   *                    └── Jira
   *
   * Instead of:
   *
   * Webhook
   *    ↓
   *   AI
   *    ↓
   * Slack
   */
  const positionedNodes =
    nodes.map((node: any) => {
      const id = String(node.id);

      const level =
        levels.get(id) ?? 0;

      const column =
        columns.get(level) ?? [];

      const row = Math.max(
        0,
        column.findIndex(
          (item) =>
            String(item.node.id) ===
            id,
        ),
      );

      const columnHeight =
        (column.length - 1) *
        NODE_Y_GAP;

      /*
       * Center branches around y = 0.
       *
       * One node:
       *     y = 0
       *
       * Three nodes:
       *     y = -150
       *     y = 0
       *     y = 150
       */
      const y =
        row * NODE_Y_GAP -
        columnHeight / 2;

      return {
        ...node,

        position: {
          x: level * NODE_X_GAP,
          y,
        },
      };
    });

  /*
   * ------------------------------------------------------------------------
   * NO-EDGE FALLBACK
   * ------------------------------------------------------------------------
   *
   * If the AI returns multiple independent nodes with no connections,
   * keep them in one horizontal row instead of putting everything in
   * one vertical column.
   */
  if (edges.length === 0) {
    return {
      ...graph,

      nodes: positionedNodes.map(
        (node: any, index: number) => ({
          ...node,

          position: {
            x: index * NODE_X_GAP,
            y: 0,
          },
        }),
      ),
    };
  }

  /*
   * Return the original graph structure with only node positions changed.
   */
  return {
    ...graph,

    nodes: positionedNodes,
  };
}

/* ==========================================================================
   STATUS INDICATOR
   ========================================================================== */

function StatusIndicator({
  status,
}: {
  status: string;
}) {
  const normalized =
    status.toUpperCase();

  return (
    <span className="flex items-center gap-1.5">
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full",

          normalized === "PUBLISHED"
            ? "bg-emerald-400"
            : normalized === "DRAFT"
              ? "bg-white/30"
              : "bg-white/20",
        )}
      />

      <span
        className={cn(
          "text-[11px] font-medium capitalize",

          normalized === "PUBLISHED"
            ? "text-emerald-400/90"
            : "text-white/35",
        )}
      >
        {status.toLowerCase()}
      </span>
    </span>
  );
}

/* ==========================================================================
   HOME PAGE
   ========================================================================== */

export default function HomePage() {
  const router = useRouter();

  const [prompt, setPrompt] =
    useState("");

  const [generating, setGenerating] =
    useState(false);

  const [openingBuilder, setOpeningBuilder] =
    useState(false);

  const [result, setResult] =
    useState<{
      graph?: any;
      notes?: string | null;
    } | null>(null);

  const [error, setError] =
    useState<string | null>(null);

  /* ------------------------------------------------------------------------
     RECENT WORKFLOWS
     ------------------------------------------------------------------------ */

  const workflowsQ = useQuery({
    queryKey: ["home-workflows"],

    queryFn: () =>
      fetchWorkflows(),

    staleTime: 30_000,
  });

  const workflows = (
    workflowsQ.data?.workflows ?? []
  ).slice(0, 6);

  /* ------------------------------------------------------------------------
     GENERATE WORKFLOW
     ------------------------------------------------------------------------ */

  const handleGenerate =
    useCallback(async () => {
      const value =
        prompt.trim();

      if (
        !value ||
        generating
      ) {
        return;
      }

      setGenerating(true);

      setError(null);

      setResult(null);

      try {
        const response =
          await generateWorkflow(
            value,
          );

        setResult(
          response ?? null,
        );
      } catch {
        setError(
          "Workflow generation failed. Check that the AI service is available and try again.",
        );
      } finally {
        setGenerating(false);
      }
    }, [
      prompt,
      generating,
    ]);

  /* ------------------------------------------------------------------------
     OPEN GENERATED WORKFLOW
     ------------------------------------------------------------------------ */

  const handleOpenGeneratedWorkflow =
    useCallback(async () => {
      if (
        !result?.graph ||
        openingBuilder
      ) {
        return;
      }

      setOpeningBuilder(true);

      setError(null);

      try {
        const created =
          await createWorkflow({
            name:
              "AI Generated Workflow",

            description:
              result.notes ?? "",
          });

        if (!created?.id) {
          throw new Error(
            "Workflow creation did not return an ID.",
          );
        }

        /*
         * IMPORTANT:
         *
         * The AI service can return nodes with vertical positions.
         * The Vantis builder uses a horizontal left-to-right layout.
         *
         * Normalize the graph BEFORE saving it so the workflow opens
         * horizontally in the existing builder.
         */
        const horizontalGraph =
          layoutGraphHorizontally(
            result.graph,
          );

        await saveWorkflowGraph(
          created.id,
          horizontalGraph,
        );

        router.push(
          `/workflows/${created.id}`,
        );
      } catch {
        setError(
          "The workflow was generated, but Vantis could not open it in the builder.",
        );
      } finally {
        setOpeningBuilder(false);
      }
    }, [
      result,
      openingBuilder,
      router,
    ]);

  /* ------------------------------------------------------------------------
     STARTER ACTIONS
     ------------------------------------------------------------------------ */

  const starterActions = [
    {
      title: "Create Workflow",

      description:
        "Build visually from scratch",

      href: "/workflows/new",

      icon: Plus,
    },

    {
      title: "Create AI Agent",

      description:
        "Build an autonomous agent",

      href: "/agents",

      icon: Bot,
    },

    {
      title: "Browse Templates",

      description:
        "Start from a proven workflow",

      href: "/templates",

      icon: LayoutTemplate,
    },
  ];

  /* ------------------------------------------------------------------------
     RENDER
     ------------------------------------------------------------------------ */

  return (
    <main className="mx-auto w-full max-w-[1180px] px-6 pb-16 pt-10 lg:px-8">
      {/* =========================================================
          HERO
         ========================================================= */}

      <section className="flex flex-col items-center text-center">
        <h1 className="text-[32px] font-semibold tracking-[-0.035em] text-white sm:text-[38px]">
          What do you want to build?
        </h1>

        <p className="mt-2 max-w-xl text-sm leading-6 text-white/40">
          Describe what you want to automate and Vantis will turn it into a
          workflow.
        </p>
      </section>

      {/* =========================================================
          AI COMPOSER
         ========================================================= */}

      <section className="mx-auto mt-7 w-full max-w-[900px]">
        <div
          className={cn(
            "overflow-hidden rounded-xl border bg-[#0b0b0b]",
            "border-white/[0.09]",
            "shadow-[0_20px_70px_rgba(0,0,0,0.22)]",
          )}
        >
          {/* Composer header */}

          <div className="flex items-center gap-3 border-b border-white/[0.065] px-5 py-3.5">
            <div className="flex size-7 items-center justify-center rounded-md border border-white/[0.09] bg-white/[0.035]">
              <Sparkles className="size-3.5 text-white/75" />
            </div>

            <div className="text-left">
              <p className="text-sm font-medium text-white/90">
                Build with AI
              </p>

              <p className="mt-0.5 text-[11px] text-white/35">
                Describe the workflow you want to create.
              </p>
            </div>
          </div>

          {/* Composer body */}

          <div className="p-4">
            <textarea
              id="workflow-prompt"
              value={prompt}
              onChange={(event) =>
                setPrompt(
                  event.target.value,
                )
              }
              onKeyDown={(event) => {
                if (
                  event.key === "Enter" &&
                  (event.metaKey ||
                    event.ctrlKey)
                ) {
                  event.preventDefault();

                  void handleGenerate();
                }
              }}
              disabled={generating}
              rows={4}
              placeholder="When a critical GitHub issue is created, analyze it with AI and notify Slack..."
              className={cn(
                "block w-full resize-none rounded-lg",
                "border border-white/[0.08]",
                "bg-white/[0.02]",
                "px-4 py-3.5",
                "text-sm leading-6 text-white",
                "outline-none",
                "placeholder:text-white/40",
                "transition-colors",
                "focus:border-white/[0.16]",
                "focus:bg-white/[0.03]",
              )}
            />

            <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex min-w-0 items-center gap-2">
                <span className="hidden text-[11px] font-medium uppercase tracking-[0.12em] text-white/45 sm:block">
                  Try
                </span>

                <button
                  type="button"
                  onClick={() =>
                    setPrompt(
                      "When a critical GitHub issue is created, analyze it with AI and notify Slack.",
                    )
                  }
                  className="truncate rounded-md border border-white/[0.07] bg-white/[0.02] px-2.5 py-1.5 text-xs text-white/50 transition-colors hover:border-white/[0.14] hover:bg-white/[0.04] hover:text-white/80"
                >
                  GitHub → AI → Slack
                </button>

                <button
                  type="button"
                  onClick={() =>
                    setPrompt(
                      "When a workflow fails, investigate the failure and notify the engineering team.",
                    )
                  }
                  className="hidden truncate rounded-md border border-white/[0.07] bg-white/[0.02] px-2.5 py-1.5 text-xs text-white/50 transition-colors hover:border-white/[0.14] hover:bg-white/[0.04] hover:text-white/80 sm:block"
                >
                  Failure investigation
                </button>
              </div>

              <div className="flex items-center justify-between gap-3">
                <span className="text-[11px] text-white/45">
                  ⌘/Ctrl + Enter
                </span>

                <Button
                  onClick={() =>
                    void handleGenerate()
                  }
                  disabled={
                    generating ||
                    !prompt.trim()
                  }
                  className="h-9 rounded-md bg-white px-4 text-xs font-medium text-black transition-opacity hover:bg-white/90 disabled:opacity-30"
                >
                  <Sparkles className="mr-1.5 size-3.5" />

                  {generating
                    ? "Generating..."
                    : "Generate workflow"}
                </Button>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* =========================================================
          ERROR
         ========================================================= */}

      {error && (
        <section className="mx-auto mt-4 w-full max-w-[900px]">
          <div className="flex items-center justify-between gap-4 rounded-lg border border-red-500/20 bg-red-500/[0.035] px-4 py-3">
            <p className="text-xs text-red-300">
              {error}
            </p>

            <button
              type="button"
              onClick={() =>
                void handleGenerate()
              }
              disabled={
                !prompt.trim() ||
                generating
              }
              className="shrink-0 text-xs text-red-300 underline-offset-4 hover:underline"
            >
              Retry
            </button>
          </div>
        </section>
      )}

      {/* =========================================================
          GENERATED RESULT
         ========================================================= */}

      {result && (
        <section className="mx-auto mt-4 w-full max-w-[900px]">
          <div className="flex flex-col gap-4 rounded-lg border border-emerald-500/20 bg-emerald-500/[0.025] px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />

                <p className="text-sm font-medium text-white">
                  Workflow generated
                </p>
              </div>

              {result.notes && (
                <p className="mt-1 text-xs leading-5 text-white/40">
                  {result.notes}
                </p>
              )}
            </div>

            {result.graph && (
              <Button
                onClick={() =>
                  void handleOpenGeneratedWorkflow()
                }
                disabled={
                  openingBuilder
                }
                className="h-9 shrink-0 rounded-md bg-white px-4 text-xs font-medium text-black hover:bg-white/90"
              >
                {openingBuilder
                  ? "Opening..."
                  : "Open workflow"}

                <ArrowRight className="ml-1.5 size-3.5" />
              </Button>
            )}
          </div>
        </section>
      )}

      {/* =========================================================
          START BUILDING
         ========================================================= */}

      <section className="mx-auto mt-11 w-full max-w-[1100px]">
        <div className="mb-3">
          <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-white/45">
            Start building
          </p>
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          {starterActions.map(
            (action) => {
              const Icon =
                action.icon;

              return (
                <Link
                  key={action.title}
                  href={action.href}
                  className="group"
                >
                  <div className="flex h-[82px] items-center gap-3 rounded-lg border border-white/[0.075] bg-[#0a0a0a] px-4 transition-all duration-150 hover:border-white/[0.14] hover:bg-white/[0.025]">
                    <div className="flex size-9 shrink-0 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.025]">
                      <Icon className="size-4 text-white/50 transition-colors group-hover:text-white/80" />
                    </div>

                    <div className="min-w-0">
                      <p className="text-sm font-medium text-white/85">
                        {action.title}
                      </p>

                      <p className="mt-1 text-xs text-white/50">
                        {action.description}
                      </p>
                    </div>

                    <ArrowRight className="ml-auto size-3.5 text-white/30 transition-all group-hover:translate-x-0.5 group-hover:text-white/70" />
                  </div>
                </Link>
              );
            },
          )}
        </div>
      </section>

      {/* =========================================================
          RECENT WORKFLOWS
         ========================================================= */}

      <section className="mx-auto mt-11 w-full max-w-[1100px]">
        <div className="mb-3 flex items-center justify-between">
          <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-white/45">
            Recent workflows
          </p>

          <Link
            href="/workflows"
            className="flex items-center gap-1 text-xs text-white/45 transition-colors hover:text-white/80"
          >
            View all

            <ArrowRight className="size-3" />
          </Link>
        </div>

        {workflowsQ.isPending ? (
          <div className="space-y-2">
            {[1, 2, 3].map(
              (item) => (
                <Skeleton
                  key={item}
                  className="h-[70px] rounded-lg bg-white/[0.035]"
                />
              ),
            )}
          </div>
        ) : workflowsQ.isError ? (
          <div className="rounded-lg border border-white/[0.075] bg-[#0a0a0a] px-5 py-10 text-center">
            <p className="text-sm text-white/50">
              Unable to load recent workflows.
            </p>
          </div>
        ) : workflows.length === 0 ? (
          <div className="rounded-lg border border-dashed border-white/[0.09] bg-[#0a0a0a] px-5 py-12 text-center">
            <p className="text-sm text-white/60">
              No workflows yet.
            </p>

            <Link
              href="/workflows/new"
              className="mt-2 inline-flex items-center text-xs text-white/60 hover:text-white"
            >
              Create your first workflow

              <ArrowRight className="ml-1 size-3" />
            </Link>
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-white/[0.075] bg-[#0a0a0a]">
            {workflows.map(
              (
                workflow,
                index,
              ) => (
                <Link
                  key={workflow.id}
                  href={`/workflows/${workflow.id}`}
                  className={cn(
                    "group flex min-h-[70px] items-center gap-4 px-5 transition-colors hover:bg-white/[0.025]",

                    index !==
                      workflows.length - 1 &&
                      "border-b border-white/[0.06]",
                  )}
                >
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-white/85 group-hover:text-white">
                      {workflow.name}
                    </p>

                    <p className="mt-1 truncate text-xs text-white/50">
                      {workflow.description ||
                        "No description"}
                    </p>
                  </div>

                  <div className="hidden shrink-0 sm:block">
                    <StatusIndicator
                      status={
                        workflow.status
                      }
                    />
                  </div>

                  <span className="hidden text-[11px] text-white/45 md:block">
                    {workflow.updatedAt
                      ? new Date(
                          workflow.updatedAt,
                        ).toLocaleDateString()
                      : "—"}
                  </span>

                  <ArrowRight className="size-3.5 shrink-0 text-white/30 transition-all group-hover:translate-x-0.5 group-hover:text-white/70" />
                </Link>
              ),
            )}
          </div>
        )}
      </section>
    </main>
  );
}