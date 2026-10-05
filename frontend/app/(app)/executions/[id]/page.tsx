"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import {
  AlertTriangle,
  ArrowLeft,
  Check,
  ChevronDown,
  PauseCircle,
  RefreshCw,
  X,
  LayoutGrid,
  List,
} from "lucide-react";

import { toast } from "sonner";

import {
  decideApproval,
  fetchExecution,
  fetchWorkflowVersion,
  getErrorMessage,
  retryExecution,
  cancelExecution,
} from "@/lib/api";

import { openExecutionStream } from "@/lib/execution-stream";

import type {
  ExecutionDetail,
  ExecutionLogEntry,
  ExecutionNodeState,
  ExecutionStatus,
  ExecutionSummary,
  LogLevel,
  WorkflowGraph,
} from "@/types";

import {
  executionStatusVariant,
  formatDateTime,
  formatDuration,
  formatRelativeTime,
  isLiveStatus,
} from "@/lib/format";

import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import {
  ErrorState,
  PageContainer,
  PageHeader,
  StatusBadge,
  TableSkeleton,
} from "@/components/app/page-primitives";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

import dynamic from "next/dynamic";

const ExecutionGraph = dynamic(
  () => import("@/components/app/execution/ExecutionGraph").then((mod) => mod.ExecutionGraph),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-[420px] w-full items-center justify-center rounded-xl border border-white/[0.08] bg-[#0a0a0c]">
        <div className="size-6 animate-spin rounded-full border-2 border-white/20 border-t-white" />
      </div>
    ),
  },
);
import { ExecutionInspector } from "@/components/app/execution/ExecutionInspector";
import { ExecutionSummaryStrip } from "@/components/app/execution/ExecutionSummaryStrip";
import { TimelineNodeRow } from "@/components/app/execution/TimelineNodeRow";

/* --------------------------------------------------------------- live state */

/**
 * The volatile slice of a run — everything the worker mutates as it executes.
 */
interface LiveState {
  status: ExecutionStatus;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  nodes: ExecutionNodeState[];
  logs: ExecutionLogEntry[];
}

type LiveAction =
  | {
      type: "seed";
      detail: ExecutionDetail;
    }
  | {
      type: "execution";
      execution: ExecutionSummary;
    }
  | {
      type: "node";
      node: ExecutionNodeState;
    }
  | {
      type: "log";
      log: ExecutionLogEntry;
    };

const EMPTY_STATE: LiveState = {
  status: "QUEUED",
  error: null,
  startedAt: null,
  finishedAt: null,
  durationMs: null,
  nodes: [],
  logs: [],
};

/**
 * Merge the authoritative fetch (`seed`) with live SSE deltas.
 *
 * Node frames upsert by graph node id, preserving order.
 * Log frames deduplicate by their monotonic `seq`.
 *
 * An `execution` frame patches only run-level fields —
 * never node/log arrays.
 */
function liveReducer(
  state: LiveState,
  action: LiveAction,
): LiveState {
  switch (action.type) {
    case "seed": {
      const d = action.detail;

      return {
        status: d.status,
        error: d.error,
        startedAt: d.startedAt,
        finishedAt: d.finishedAt,
        durationMs: d.durationMs,
        nodes: d.nodes,
        logs: [...d.logs].sort(
          (a, b) => a.seq - b.seq,
        ),
      };
    }

    case "execution": {
      const e = action.execution;

      return {
        ...state,
        status: e.status,
        error: e.error,
        startedAt: e.startedAt,
        finishedAt: e.finishedAt,
        durationMs: e.durationMs,
      };
    }

    case "node": {
      const idx = state.nodes.findIndex(
        (n) => n.nodeId === action.node.nodeId,
      );

      const nodes =
        idx === -1
          ? [...state.nodes, action.node]
          : state.nodes.map((n, i) =>
              i === idx ? action.node : n,
            );

      return {
        ...state,
        nodes,
      };
    }

    case "log": {
      if (
        state.logs.some(
          (l) => l.seq === action.log.seq,
        )
      ) {
        return state;
      }

      return {
        ...state,
        logs: [
          ...state.logs,
          action.log,
        ].sort(
          (a, b) => a.seq - b.seq,
        ),
      };
    }
  }
}

const MAX_STREAM_FAILURES = 5;

/* ========================================================================= */

export default function ExecutionDetailPage() {
  const params = useParams<{ id: string }>();

  const id = params.id;

  const queryClient = useQueryClient();

  /* ------------------------------------------------------------------------
   * Execution detail
   * ---------------------------------------------------------------------- */

  const detailQuery = useQuery({
    queryKey: ["execution", id],

    queryFn: () => fetchExecution(id),

    enabled: Boolean(id),
  });

  const [state, dispatch] = React.useReducer(
    liveReducer,
    EMPTY_STATE,
  );

  const [seeded, setSeeded] =
    React.useState(false);

  /*
   * Seed once from the initial fetch.
   *
   * Later re-seeds are explicit through decide/retry/cancel because those
   * operations return a fresh authoritative execution detail.
   */
  React.useEffect(() => {
    if (
      detailQuery.data &&
      !seeded
    ) {
      dispatch({
        type: "seed",
        detail: detailQuery.data,
      });

      setSeeded(true);
    }
  }, [
    detailQuery.data,
    seeded,
  ]);

  /* ------------------------------------------------------------------------
   * Live execution stream
   * ---------------------------------------------------------------------- */

  const [streamNonce, setStreamNonce] =
    React.useState(0);

  const [connected, setConnected] =
    React.useState(false);

  const [disconnected, setDisconnected] =
    React.useState(false);

  const statusRef =
    React.useRef<ExecutionStatus>(
      "QUEUED",
    );

  const failuresRef =
    React.useRef(0);

  React.useEffect(() => {
    statusRef.current =
      state.status;
  }, [state.status]);

  const reconnect =
    React.useCallback(() => {
      failuresRef.current = 0;

      setDisconnected(false);

      setStreamNonce(
        (n) => n + 1,
      );
    }, []);

  React.useEffect(() => {
    if (!id) {
      return;
    }

    let retimer:
      | ReturnType<typeof setTimeout>
      | undefined;

    const liveNow = () =>
      statusRef.current === "QUEUED" ||
      statusRef.current === "RUNNING";

    setConnected(true);

    const stop =
      openExecutionStream(id, {
        onExecution: (execution) => {
          failuresRef.current = 0;

          dispatch({
            type: "execution",
            execution,
          });
        },

        onNode: (node) => {
          dispatch({
            type: "node",
            node,
          });
        },

        onLog: (log) => {
          dispatch({
            type: "log",
            log,
          });
        },

        onDone: () => {
          setConnected(false);

          /*
           * A graceful close while the execution is still live can be
           * the server's SSE timeout. Re-open the stream.
           */
          if (liveNow()) {
            retimer = setTimeout(
              () =>
                setStreamNonce(
                  (n) => n + 1,
                ),
              1000,
            );
          }
        },

        onError: () => {
          setConnected(false);

          failuresRef.current += 1;

          if (
            liveNow() &&
            failuresRef.current <=
              MAX_STREAM_FAILURES
          ) {
            retimer = setTimeout(
              () =>
                setStreamNonce(
                  (n) => n + 1,
                ),
              Math.min(
                1000 *
                  failuresRef.current,
                5000,
              ),
            );
          } else {
            setDisconnected(true);
          }
        },
      });

    return () => {
      stop();

      if (retimer) {
        clearTimeout(retimer);
      }
    };
  }, [
    id,
    streamNonce,
  ]);

  /* ------------------------------------------------------------------------
   * Mutations
   * ---------------------------------------------------------------------- */

  const reseedFrom =
    React.useCallback(
      (detail: ExecutionDetail) => {
        queryClient.setQueryData(
          ["execution", id],
          detail,
        );

        dispatch({
          type: "seed",
          detail,
        });

        reconnect();
      },
      [
        id,
        queryClient,
        reconnect,
      ],
    );

  const decideMutation =
    useMutation({
      mutationFn: (vars: {
        nodeId: string;
        approved: boolean;
        note: string;
      }) =>
        decideApproval(
          id,
          vars.nodeId,
          {
            approved:
              vars.approved,
            note:
              vars.note ||
              undefined,
          },
        ),

      onSuccess: (
        detail,
        vars,
      ) => {
        reseedFrom(detail);

        toast.success(
          vars.approved
            ? "Approved — resuming run."
            : "Rejected — resuming run.",
        );
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(err),
        ),
    });

  const retryMutation =
    useMutation({
      mutationFn: () =>
        retryExecution(id),

      onSuccess: (detail) => {
        reseedFrom(detail);

        toast.success(
          "Retrying the failed run.",
        );
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(err),
        ),
    });

  const cancelMutation =
    useMutation({
      mutationFn: () =>
        cancelExecution(id),

      onSuccess: (detail) => {
        reseedFrom(detail);

        toast.success(
          "Run canceled.",
        );
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(err),
        ),
    });

  /* ------------------------------------------------------------------------
   * View & selection state
   * ---------------------------------------------------------------------- */

  const [view, setView] =
    React.useState<
      "graph" | "timeline"
    >("graph");

  const [
    selectedNodeId,
    setSelectedNodeId,
  ] = React.useState<
    string | undefined
  >();

  /*
   * IMPORTANT:
   *
   * An execution stores the exact workflow version that ran.
   *
   * We therefore fetch that immutable workflow version and use its graph
   * instead of expecting `workflowGraph` to exist on ExecutionDetail.
   *
   * This guarantees that the execution graph matches the exact workflow
   * version that produced this execution.
   */
  const workflowVersionQuery =
    useQuery({
      queryKey: [
        "workflow-version",
        detailQuery.data
          ?.workflowId,
        detailQuery.data
          ?.versionNumber,
      ],

      queryFn: () =>
        fetchWorkflowVersion(
          detailQuery.data!
            .workflowId,
          detailQuery.data!
            .versionNumber,
        ),

      enabled:
        Boolean(
          detailQuery.data
            ?.workflowId,
        ) &&
        detailQuery.data
          ?.versionNumber != null,

      staleTime: Infinity,
    });

  /*
   * The graph now comes from the immutable workflow version.
   */
  const workflowGraph:
    | WorkflowGraph
    | null =
    workflowVersionQuery
      .data?.graph ?? null;

  /* ------------------------------------------------------------------------
   * Timeline
   * ---------------------------------------------------------------------- */

  const nodesForTimeline =
    state.nodes;

  /* ------------------------------------------------------------------------
   * Selected node
   * ---------------------------------------------------------------------- */

  const selectedNode =
    React.useMemo(() => {
      if (!selectedNodeId) {
        return null;
      }

      return (
        state.nodes.find(
          (node) =>
            node.nodeId ===
            selectedNodeId,
        ) ?? null
      );
    }, [
      selectedNodeId,
      state.nodes,
    ]);

  /* ------------------------------------------------------------------------
   * Summary metrics
   * ---------------------------------------------------------------------- */

  const summary =
    React.useMemo(() => {
      const nodes =
        state.nodes;

      const total =
        nodes.length;

      let executed = 0;
      let failed = 0;
      let skipped = 0;
      let running = 0;

      for (const node of nodes) {
        switch (node.status) {
          case "SUCCEEDED":
            executed++;
            break;

          case "FAILED":
            executed++;
            failed++;
            break;

          case "SKIPPED":
            skipped++;
            break;

          case "RUNNING":
            running++;
            break;

          default:
            break;
        }
      }

      return {
        total,
        executed,
        failed,
        skipped,
        running,
      };
    }, [state.nodes]);

  /* ------------------------------------------------------------------------
   * Render guards
   * ---------------------------------------------------------------------- */

  if (
    detailQuery.isPending ||
    !seeded ||
    workflowVersionQuery.isPending
  ) {
    if (detailQuery.isError) {
      return (
        <PageContainer variant="narrow">
          <ErrorState
            title="Could not load execution"
            message={getErrorMessage(detailQuery.error)}
            onRetry={() => detailQuery.refetch()}
          />
        </PageContainer>
      );
    }

    if (workflowVersionQuery.isError) {
      return (
        <PageContainer variant="narrow">
          <ErrorState
            title="Could not load workflow version"
            message={getErrorMessage(workflowVersionQuery.error)}
            onRetry={() => workflowVersionQuery.refetch()}
          />
        </PageContainer>
      );
    }

    return (
      <PageContainer variant="narrow">
        <TableSkeleton rows={6} />
      </PageContainer>
    );
  }

  /* ------------------------------------------------------------------------
   * Authoritative metadata
   * ---------------------------------------------------------------------- */

  const meta =
    detailQuery.data!;

  const live =
    isLiveStatus(
      state.status,
    );

  const approvalNode =
    state.nodes.find(
      (node) =>
        node.nodeType ===
          "human_approval" &&
        node.status ===
          "WAITING",
    );

  const showApproval =
    state.status ===
      "WAITING" &&
    Boolean(approvalNode);

  const triggerPayloadEmpty =
    meta.triggerPayload ==
      null ||
    (typeof meta.triggerPayload ===
      "object" &&
      !Array.isArray(
        meta.triggerPayload,
      ) &&
      Object.keys(
        meta.triggerPayload as object,
      ).length === 0);

  /* ------------------------------------------------------------------------
   * Page
   * ---------------------------------------------------------------------- */

  return (
    <PageContainer variant="narrow">
      <PageHeader
        backLink={{ href: "/executions", label: "Executions" }}
        eyebrow="Execution Control Plane"
        title={
          <Link
            href={`/workflows/${meta.workflowId}`}
            className="truncate hover:underline"
          >
            {meta.workflowName ?? "Untitled workflow"}
          </Link>
        }
        description={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-white/50">
            <StatusBadge status={state.status} />
            <span>v{meta.versionNumber}</span>
            <span>{meta.triggerType.toLowerCase()} trigger</span>
            <span title={formatDateTime(meta.createdAt)}>
              started {formatRelativeTime(meta.startedAt ?? meta.createdAt)}
            </span>
            <span>·</span>
            <span>{formatDuration(state.durationMs)}</span>
            {connected && live && (
              <span className="inline-flex items-center gap-1 text-emerald-400">
                <span className="size-1.5 animate-pulse rounded-full bg-emerald-400" />
                Live
              </span>
            )}
          </span>
        }
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {state.status === "FAILED" && (
              <Button
                onClick={() => retryMutation.mutate()}
                disabled={retryMutation.isPending}
              >
                <RefreshCw
                  className={cn(
                    "size-4",
                    retryMutation.isPending && "animate-spin",
                  )}
                />
                {retryMutation.isPending ? "Retrying…" : "Retry run"}
              </Button>
            )}

            {(state.status === "QUEUED" || state.status === "RUNNING") && (
              <Button
                variant="outline"
                onClick={() => cancelMutation.mutate()}
                disabled={cancelMutation.isPending}
              >
                <X className="size-4" />
                {cancelMutation.isPending ? "Canceling…" : "Cancel run"}
              </Button>
            )}
          </div>
        }
      />

      {/* disconnected banner */}

      {disconnected && (
        <div className="flex items-center justify-between gap-3 rounded-md border border-white/[0.08] bg-white/[0.03] px-4 py-2.5 text-sm">
          <span className="text-white/44">
            Live updates disconnected.
          </span>

          <Button
            variant="outline"
            size="sm"
            onClick={reconnect}
          >
            <RefreshCw className="size-4" />

            Reconnect
          </Button>
        </div>
      )}

      {/* failure banner */}

      {state.status ===
        "FAILED" &&
        state.error && (
          <div className="flex items-start gap-2 rounded-md border border-white/[0.08] bg-white/[0.03] px-4 py-3 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 size-4 shrink-0" />

            <span className="min-w-0 break-words">
              {state.error}
            </span>
          </div>
        )}

      {/* approval panel */}

      {showApproval &&
        approvalNode && (
          <ApprovalPanel
            node={approvalNode}
            pending={
              decideMutation.isPending
            }
            onDecide={(
              approved,
              note,
            ) =>
              decideMutation.mutate({
                nodeId:
                  approvalNode.nodeId,
                approved,
                note,
              })
            }
          />
        )}

      {/* summary strip */}

      <ExecutionSummaryStrip
        durationMs={
          state.durationMs
        }
        totalNodes={
          summary.total
        }
        executed={
          summary.executed
        }
        failed={
          summary.failed
        }
        skipped={
          summary.skipped
        }
        running={
          summary.running
        }
        status={
          state.status
        }
      />

      {/* view tabs */}

      <div className="flex items-center gap-1 border-b border-white/[0.08]">
        <button
          type="button"
          onClick={() =>
            setView("graph")
          }
          className={cn(
            "flex items-center gap-2 px-4 py-2.5 text-sm font-medium transition border-b-2 -mb-px",

            view === "graph"
              ? "border-white/60 text-white/90"
              : "border-transparent text-white/44 hover:text-white/70",
          )}
        >
          <LayoutGrid className="size-4" />

          Graph
        </button>

        <button
          type="button"
          onClick={() =>
            setView("timeline")
          }
          className={cn(
            "flex items-center gap-2 px-4 py-2.5 text-sm font-medium transition border-b-2 -mb-px",

            view === "timeline"
              ? "border-white/60 text-white/90"
              : "border-transparent text-white/44 hover:text-white/70",
          )}
        >
          <List className="size-4" />

          Timeline
        </button>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* main panel */}

        <div className="space-y-3 lg:col-span-2">
          {view === "graph" ? (
            <ExecutionGraph
              workflowGraph={
                workflowGraph
              }
              executionStates={
                state.nodes
              }
              selectedNodeId={
                selectedNodeId
              }
              onNodeSelect={
                setSelectedNodeId
              }
            />
          ) : (
            /* timeline */

            <div className="space-y-1.5">
              {nodesForTimeline.length ===
              0 ? (
                <p className="rounded-xl border border-dashed border-white/[0.10] px-4 py-8 text-center text-sm text-white/44">
                  This run has no nodes.
                </p>
              ) : (
                nodesForTimeline.map(
                  (node) => (
                    <TimelineNodeRow
                      key={node.id}
                      node={node}
                      onClick={
                        setSelectedNodeId
                      }
                    />
                  ),
                )
              )}
            </div>
          )}

          {/* trigger payload */}

          {view ===
            "timeline" &&
            !triggerPayloadEmpty && (
              <details className="group rounded-xl border border-white/[0.08] [&_summary::-webkit-details-marker]:hidden">
                <summary className="flex cursor-pointer list-none items-center gap-2 px-4 py-2.5 text-sm">
                  <ChevronDown className="size-4 text-white/44 transition-transform group-open:rotate-180" />

                  <span className="font-medium">
                    Trigger payload
                  </span>
                </summary>

                <div className="border-t border-white/[0.06] p-4">
                  <JsonBlock
                    value={
                      meta.triggerPayload
                    }
                  />
                </div>
              </details>
            )}
        </div>

        {/* inspector + logs */}

        <div className="lg:col-span-1 space-y-6">
          {view === "graph" ? (
            <ExecutionInspector
              node={selectedNode}
              onRetry={(_nodeId) => {
                /*
                 * Backend retry-node support is gated.
                 * Only expose this when the API supports it.
                 */
              }}
              onClose={() =>
                setSelectedNodeId(
                  undefined,
                )
              }
            />
          ) : (
            selectedNode && (
              <ExecutionInspector
                node={selectedNode}
                onClose={() =>
                  setSelectedNodeId(
                    undefined,
                  )
                }
              />
            )
          )}

          <LogPanel
            logs={state.logs}
          />
        </div>
      </div>
    </PageContainer>
  );
}

/* ----------------------------------------------------------------- pieces */

function ApprovalPanel({
  node,
  pending,
  onDecide,
}: {
  node: ExecutionNodeState;
  pending: boolean;
  onDecide: (
    approved: boolean,
    note: string,
  ) => void;
}) {
  const [note, setNote] =
    React.useState("");

  return (
    <Card className="border-amber-500/40 bg-amber-500/5 bg-[#0a0a0a]">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <PauseCircle className="size-4 text-amber-600 dark:text-amber-400" />

          Awaiting approval
        </CardTitle>

        <CardDescription>
          “
          {node.label ??
            node.nodeType}
          ” is paused for a human
          decision. The run resumes
          down the branch you choose.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-3">
        <Textarea
          value={note}
          onChange={(event) =>
            setNote(event.target.value)
          }
          placeholder="Optional note (recorded in the run log)…"
          rows={2}
          disabled={pending}
        />

        <div className="flex gap-2">
          <Button
            onClick={() =>
              onDecide(
                true,
                note,
              )
            }
            disabled={pending}
          >
            <Check className="size-4" />

            Approve
          </Button>

          <Button
            variant="outline"
            onClick={() =>
              onDecide(
                false,
                note,
              )
            }
            disabled={pending}
          >
            <X className="size-4" />

            Reject
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function JsonBlock({
  value,
}: {
  value: unknown;
}) {
  if (
    value === null ||
    value === undefined
  ) {
    return (
      <p className="text-xs text-white/44">
        None recorded.
      </p>
    );
  }

  return (
    <pre className="max-h-64 overflow-auto rounded-md border border-white/[0.08] bg-[#0a0a0a] p-3 text-xs leading-relaxed font-mono">
      {JSON.stringify(
        value,
        null,
        2,
      )}
    </pre>
  );
}

function logLevelClass(
  level: LogLevel,
): string {
  switch (level) {
    case "ERROR":
      return "text-destructive";

    case "WARN":
      return "text-amber-400";

    case "DEBUG":
      return "text-white/45";

    default:
      return "text-white/60";
  }
}

function LogPanel({
  logs,
}: {
  logs: ExecutionLogEntry[];
}) {
  const scrollRef =
    React.useRef<HTMLOListElement>(
      null,
    );

  /*
   * Keep the newest line in view
   * as logs stream in.
   */
  React.useEffect(() => {
    const element =
      scrollRef.current;

    if (element) {
      element.scrollTop =
        element.scrollHeight;
    }
  }, [logs.length]);

  return (
    <Card className="lg:sticky lg:top-6 bg-[#0a0a0a] border-white/[0.08]">
      <CardHeader className="pb-3">
        <CardTitle className="text-base text-white/90">
          Logs
        </CardTitle>
      </CardHeader>

      <CardContent>
        {logs.length === 0 ? (
          <p className="text-sm text-white/44">
            No log output yet.
          </p>
        ) : (
          <ol
            ref={scrollRef}
            className="max-h-[60vh] space-y-1.5 overflow-auto font-mono text-xs"
          >
            {logs.map((log) => (
              <li
                key={log.id}
                className="flex gap-2"
              >
                <span
                  className={cn(
                    "w-10 shrink-0 font-medium",
                    logLevelClass(
                      log.level,
                    ),
                  )}
                >
                  {log.level}
                </span>

                <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-white/60">
                  {log.message}
                </span>
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}