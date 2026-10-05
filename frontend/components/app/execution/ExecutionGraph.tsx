"use client";

import * as React from "react";

import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";

import { fetchNodeTypes } from "@/lib/api";

import type {
  ExecutionNodeState,
  ExecutionStatus,
  NodeDefinition,
  WorkflowGraph,
} from "@/types";

import {
  ErrorNodesContext,
  graphToFlow,
  type FlowEdge,
  type FlowNode as FlowNodeType,
} from "@/components/app/builder/shared";

import {
  FlowNode,
  NodeDefsContext,
} from "@/components/app/builder/flow-node";

/* ==========================================================================
   TYPES
   ========================================================================== */

export interface ExecutionGraphProps {
  workflowGraph?: WorkflowGraph | null;

  executionStates: ExecutionNodeState[];

  executionStatus?: ExecutionStatus;

  selectedNodeId?: string;

  onNodeSelect?: (nodeId: string) => void;
}

/* ==========================================================================
   EXECUTION STATUS
   ========================================================================== */

type ExecutionCanvasStatus =
  | "PENDING"
  | "RUNNING"
  | "WAITING"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED"
  | "UNKNOWN";

/* ==========================================================================
   HELPERS
   ========================================================================== */

function getExecutionStatus(
  nodeId: string,
  stateMap: Map<string, ExecutionNodeState>,
  executionStatus?: ExecutionStatus,
): ExecutionCanvasStatus {
  const state = stateMap.get(nodeId);

  if (state?.status) {
    return state.status as ExecutionCanvasStatus;
  }

  /*
   * A node with no execution record in a completed execution
   * means that branch was not executed.
   */
  if (
    executionStatus === "SUCCEEDED" ||
    executionStatus === "FAILED" ||
    executionStatus === "CANCELED"
  ) {
    return "SKIPPED";
  }

  return "UNKNOWN";
}

/* ==========================================================================
   HORIZONTAL LAYOUT
   ========================================================================== */

function createHorizontalLayout(
  nodes: FlowNodeType[],
  edges: FlowEdge[],
): FlowNodeType[] {
  if (nodes.length === 0) {
    return [];
  }

  const nodeIds = new Set(
    nodes.map((node) => node.id),
  );

  const outgoing =
    new Map<string, string[]>();

  const incomingCount =
    new Map<string, number>();

  for (const node of nodes) {
    outgoing.set(node.id, []);
    incomingCount.set(node.id, 0);
  }

  for (const edge of edges) {
    if (
      !nodeIds.has(edge.source) ||
      !nodeIds.has(edge.target)
    ) {
      continue;
    }

    outgoing
      .get(edge.source)
      ?.push(edge.target);

    incomingCount.set(
      edge.target,
      (incomingCount.get(edge.target) ?? 0) + 1,
    );
  }

  const queue: string[] = [];

  for (const node of nodes) {
    if (
      (incomingCount.get(node.id) ?? 0) === 0
    ) {
      queue.push(node.id);
    }
  }

  if (queue.length === 0) {
    queue.push(nodes[0].id);
  }

  const levels =
    new Map<string, number>();

  for (const node of nodes) {
    levels.set(node.id, 0);
  }

  const remainingIncoming =
    new Map(incomingCount);

  const processed =
    new Set<string>();

  while (queue.length > 0) {
    const currentId =
      queue.shift()!;

    if (processed.has(currentId)) {
      continue;
    }

    processed.add(currentId);

    const currentLevel =
      levels.get(currentId) ?? 0;

    for (const targetId of
      outgoing.get(currentId) ?? []) {
      levels.set(
        targetId,
        Math.max(
          levels.get(targetId) ?? 0,
          currentLevel + 1,
        ),
      );

      const remaining =
        (remainingIncoming.get(targetId) ?? 0) - 1;

      remainingIncoming.set(
        targetId,
        remaining,
      );

      if (remaining <= 0) {
        queue.push(targetId);
      }
    }
  }

  let maxLevel = 0;

  for (const level of levels.values()) {
    maxLevel =
      Math.max(maxLevel, level);
  }

  for (const node of nodes) {
    if (!processed.has(node.id)) {
      maxLevel += 1;

      levels.set(
        node.id,
        maxLevel,
      );
    }
  }

  const columns =
    new Map<
      number,
      FlowNodeType[]
    >();

  for (const node of nodes) {
    const level =
      levels.get(node.id) ?? 0;

    const column =
      columns.get(level) ?? [];

    column.push(node);

    columns.set(
      level,
      column,
    );
  }

  for (const column of columns.values()) {
    column.sort((a, b) => {
      const ay =
        a.position?.y ?? 0;

      const by =
        b.position?.y ?? 0;

      return ay - by;
    });
  }

  const HORIZONTAL_GAP = 190;
  const VERTICAL_GAP = 155;

  const result: FlowNodeType[] = [];

  for (const [
    level,
    column,
  ] of columns.entries()) {
    const totalHeight =
      (column.length - 1) *
      VERTICAL_GAP;

    const startY =
      -totalHeight / 2;

    column.forEach(
      (node, index) => {
        result.push({
          ...node,

          position: {
            x:
              level *
              HORIZONTAL_GAP,

            y:
              startY +
              index *
                VERTICAL_GAP,
          },
        });
      },
    );
  }

  return result;
}

/* ==========================================================================
   EXECUTION FLOW NODE

   This does NOT create another visual box.

   It simply forwards the execution status through node data to FlowNode.
   FlowNode itself applies the border color to its existing node body.
   ========================================================================== */

function ExecutionFlowNode(
  props: NodeProps,
) {
  return (
    <FlowNode {...props} />
  );
}

/* ==========================================================================
   EDGE HANDLE NORMALIZATION
   ========================================================================== */

function normalizeEdges(
  edges: FlowEdge[],
  nodes: FlowNodeType[],
  defsByType: Record<
    string,
    NodeDefinition
  >,
): FlowEdge[] {
  const nodeMap =
    new Map(
      nodes.map((node) => [
        node.id,
        node,
      ]),
    );

  return edges.map((edge) => {
    const sourceNode =
      nodeMap.get(edge.source);

    if (!sourceNode) {
      return edge;
    }

    const definition = sourceNode.type
  ? defsByType[sourceNode.type]
  : undefined;

const outputs =
  definition?.outputs?.length
    ? definition.outputs
    : ["out"];

    /*
     * Preserve an existing source handle.
     *
     * Only provide a fallback when missing.
     */
    const sourceHandle =
      edge.sourceHandle ??
      outputs[0];

    return {
      ...edge,
      sourceHandle,
    };
  });
}

/* ==========================================================================
   MAIN COMPONENT
   ========================================================================== */

export function ExecutionGraph(
  props: ExecutionGraphProps,
) {
  return (
    <ReactFlowProvider>
      <ExecutionGraphInner
        {...props}
      />
    </ReactFlowProvider>
  );
}

/* ==========================================================================
   INNER
   ========================================================================== */

function ExecutionGraphInner({
  workflowGraph,
  executionStates,
  executionStatus,
  selectedNodeId,
  onNodeSelect,
}: ExecutionGraphProps) {
  const { fitView } =
    useReactFlow<
      FlowNodeType,
      FlowEdge
    >();

  /* ------------------------------------------------------------------------
     NODE DEFINITIONS
     ------------------------------------------------------------------------ */

  const [nodeDefs, setNodeDefs] =
    React.useState<
      NodeDefinition[]
    >([]);

  React.useEffect(() => {
    let cancelled = false;

    async function loadNodeDefinitions() {
      try {
        const result =
          await fetchNodeTypes();

        if (!cancelled) {
          setNodeDefs(
            result.nodeTypes ?? [],
          );
        }
      } catch {
        if (!cancelled) {
          setNodeDefs([]);
        }
      }
    }

    void loadNodeDefinitions();

    return () => {
      cancelled = true;
    };
  }, []);

  /* ------------------------------------------------------------------------
     NODE DEFINITION MAP
     ------------------------------------------------------------------------ */

  const defsByType =
    React.useMemo(() => {
      const map: Record<
        string,
        NodeDefinition
      > = {};

      for (const definition of
        nodeDefs) {
        map[definition.type] =
          definition;
      }

      return map;
    }, [nodeDefs]);

  /* ------------------------------------------------------------------------
     ORIGINAL WORKFLOW GRAPH
     ------------------------------------------------------------------------ */

  const originalGraph =
    React.useMemo(() => {
      if (!workflowGraph) {
        return {
          nodes:
            [] as FlowNodeType[],

          edges:
            [] as FlowEdge[],
        };
      }

      return graphToFlow(
        workflowGraph,
      );
    }, [workflowGraph]);

  /* ------------------------------------------------------------------------
     NORMALIZE EDGES
     ------------------------------------------------------------------------ */

  const normalizedEdges =
    React.useMemo(() => {
      return normalizeEdges(
        originalGraph.edges,
        originalGraph.nodes,
        defsByType,
      );
    }, [
      originalGraph.edges,
      originalGraph.nodes,
      defsByType,
    ]);

  /* ------------------------------------------------------------------------
     HORIZONTAL EXECUTION LAYOUT
     ------------------------------------------------------------------------ */

  const layoutNodes =
    React.useMemo(() => {
      return createHorizontalLayout(
        originalGraph.nodes,
        normalizedEdges,
      );
    }, [
      originalGraph.nodes,
      normalizedEdges,
    ]);

  /* ------------------------------------------------------------------------
     EXECUTION STATE MAP
     ------------------------------------------------------------------------ */

  const stateMap =
    React.useMemo(() => {
      return new Map(
        executionStates.map(
          (state) => [
            state.nodeId,
            state,
          ],
        ),
      );
    }, [executionStates]);

  /* ------------------------------------------------------------------------
     FAILED NODES
     ------------------------------------------------------------------------ */

  const errorNodeIds =
    React.useMemo(() => {
      const result =
        new Set<string>();

      for (const state of
        executionStates) {
        if (
          state.status ===
          "FAILED"
        ) {
          result.add(
            state.nodeId,
          );
        }
      }

      return result;
    }, [executionStates]);

  /* ------------------------------------------------------------------------
     FINAL NODES
     ------------------------------------------------------------------------ */

  const nodes =
    React.useMemo<
      FlowNodeType[]
    >(() => {
      return layoutNodes.map(
        (node) => {
          const state =
            stateMap.get(
              node.id,
            );

          const status =
            getExecutionStatus(
              node.id,
              stateMap,
              executionStatus,
            );

          return {
            ...node,

            selected:
              selectedNodeId ===
              node.id,

            data: {
              ...node.data,

              label:
  node.data?.label ??
  node.id,

              /*
               * FlowNode reads this value directly.
               *
               * SUCCEEDED -> green existing border
               * FAILED    -> red existing border
               * everything else -> normal border
               */
              executionStatus:
                status,

              executionDurationMs:
                state?.durationMs ??
                null,

              executionError:
                state?.error ??
                null,

              executionAttempt:
                state?.attempt ??
                null,
            },
          };
        },
      );
    }, [
      layoutNodes,
      stateMap,
      executionStatus,
      selectedNodeId,
    ]);

  /* ------------------------------------------------------------------------
     EDGES

     NO EXECUTION STATUS COLORING.
     NO ANIMATION.

     All connections remain neutral.
     ------------------------------------------------------------------------ */

  const edges =
    React.useMemo<FlowEdge[]>(
      () => {
        return normalizedEdges.map(
          (edge) => ({
            ...edge,

            source:
              edge.source,

            target:
              edge.target,

            sourceHandle:
              edge.sourceHandle,

            targetHandle:
              edge.targetHandle,

            animated: false,

            style: {
              ...(edge.style ?? {}),

              stroke:
                "rgba(255,255,255,0.16)",

              strokeWidth: 1.4,

              opacity: 1,
            },
          }),
        );
      },
      [
        normalizedEdges,
      ],
    );

  /* ------------------------------------------------------------------------
     NODE TYPES
     ------------------------------------------------------------------------ */

  const nodeTypes =
    React.useMemo<NodeTypes>(
      () => {
        const map: NodeTypes =
          {};

        for (const definition of
          nodeDefs) {
          map[
            definition.type
          ] =
            ExecutionFlowNode;
        }

        /*
         * Support immutable workflow versions that contain
         * an older node type no longer in the registry.
         */
        for (const node of
          nodes) {
          if (
            node.type &&
            !map[node.type]
          ) {
            map[node.type] =
              ExecutionFlowNode;
          }
        }

        return map;
      },
      [
        nodeDefs,
        nodes,
      ],
    );

  /* ------------------------------------------------------------------------
     CLICK
     ------------------------------------------------------------------------ */

  const handleNodeClick =
    React.useCallback(
      (
        _event: React.MouseEvent,
        node: Node,
      ) => {
        onNodeSelect?.(
          node.id,
        );
      },
      [onNodeSelect],
    );

  /* ------------------------------------------------------------------------
     FIT VIEW
     ------------------------------------------------------------------------ */

  React.useEffect(() => {
    if (nodes.length === 0) {
      return;
    }

    const timer =
      window.setTimeout(
        () => {
          fitView({
            duration: 450,
            padding: 0.18,
          });
        },
        100,
      );

    return () =>
      window.clearTimeout(
        timer,
      );
  }, [
    nodes.length,
    workflowGraph,
    fitView,
  ]);

  /* ------------------------------------------------------------------------
     EMPTY
     ------------------------------------------------------------------------ */

  if (
    !workflowGraph ||
    nodes.length === 0
  ) {
    return (
      <div className="flex h-[560px] items-center justify-center rounded-xl border border-white/[0.08] bg-[#050505]">
        <div className="text-center">
          <p className="text-sm font-medium text-white/70">
            No workflow graph available
          </p>

          <p className="mt-1 text-xs text-white/45">
            This execution does not have a frozen workflow graph.
          </p>
        </div>
      </div>
    );
  }

  /* ------------------------------------------------------------------------
     RENDER
     ------------------------------------------------------------------------ */

  return (
    <ErrorNodesContext.Provider
      value={errorNodeIds}
    >
      <NodeDefsContext.Provider
        value={defsByType}
      >
        <div className="relative h-[560px] w-full overflow-hidden rounded-xl border border-white/[0.08] bg-[#050505]">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodeClick={
              handleNodeClick
            }
            fitView
            minZoom={0.25}
            maxZoom={2}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable
            panOnDrag
            zoomOnScroll
            zoomOnPinch
            zoomOnDoubleClick
            proOptions={{
              hideAttribution: true,
            }}
            className="bg-[#050505]"
          >
            <Background
              className="!bg-white/[0.04]"
              gap={16}
            />

            <Controls
              className="!shadow-md"
            />

            <MiniMap
              pannable
              zoomable
              className="!bg-card"
              nodeClassName={() =>
                "!fill-primary/40"
              }
            />
          </ReactFlow>

          {/* ================================================================
              EXECUTION LEGEND
              ================================================================ */}

          <div className="pointer-events-none absolute bottom-4 left-4 z-10 flex items-center gap-3 rounded-lg border border-white/[0.07] bg-[#090909]/90 px-3 py-2 backdrop-blur">
            <LegendItem
              dotClass="bg-emerald-400"
              label="Succeeded"
            />

            <LegendItem
              dotClass="bg-red-400"
              label="Failed"
            />

            <LegendItem
              dotClass="bg-white/30"
              label="Skipped"
            />

            <LegendItem
              dotClass="bg-blue-400"
              label="Running"
            />
          </div>
        </div>
      </NodeDefsContext.Provider>
    </ErrorNodesContext.Provider>
  );
}

/* ==========================================================================
   LEGEND ITEM
   ========================================================================== */

function LegendItem({
  dotClass,
  label,
}: {
  dotClass: string;
  label: string;
}) {
  return (
    <div className="flex items-center gap-1.5">
      <span
        className={`size-1.5 rounded-full ${dotClass}`}
      />

      <span className="text-[10px] text-white/40">
        {label}
      </span>
    </div>
  );
}