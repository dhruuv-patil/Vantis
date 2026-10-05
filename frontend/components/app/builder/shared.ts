import * as React from "react";
import * as Icons from "lucide-react";
import { Boxes, type LucideIcon } from "lucide-react";
import type { Node, Edge } from "@xyflow/react";
import type {
  GraphEdge,
  GraphNode,
  NodeCategory,
  NodeDefinition,
  WorkflowGraph,
} from "@/types";

/* ------------------------------------------------------------------ icons */

/**
 * Resolve a lucide icon by the PascalCase name the backend registry stores on
 * `NodeDefinition.icon`. Falls back to a generic block icon so an unknown name
 * never crashes the palette or a node.
 */
export function resolveIcon(name: string | undefined | null): LucideIcon {
  if (!name) return Boxes;
  const lib = Icons as unknown as Record<string, LucideIcon | undefined>;
  return lib[name] ?? Boxes;
}

/* ------------------------------------------------------- node data / typing */

/**
 * The `data` payload carried on every React Flow node. We keep the backend
 * node-type key on `node.type` (see the graph converters below), so `data` only
 * needs the human label and the per-node config bag.
 */
export interface FlowNodeData extends Record<string, unknown> {
  label?: string;
  config?: Record<string, unknown>;
}

export type FlowNode = Node<FlowNodeData>;
export type FlowEdge = Edge;

/* --------------------------------------------------- error highlight context */

/**
 * Set of node ids that currently have a validation ERROR. Provided by the page
 * so the custom node component can draw a destructive ring without threading
 * props through React Flow's node renderer.
 */
export const ErrorNodesContext = React.createContext<Set<string>>(new Set());

/* -------------------------------------------------------- graph <-> flow map */

/**
 * Backend graph → React Flow. The shapes are nearly identical; we only ensure
 * `position` exists (older/empty graphs may omit it) and copy the label/config
 * into `data`. Crucially we keep the backend `type` as the RF node `type` so a
 * round-trip save preserves the registry key the backend reads.
 */
export function graphToFlow(graph: WorkflowGraph): {
  nodes: FlowNode[];
  edges: FlowEdge[];
} {
  const nodes: FlowNode[] = (graph.nodes ?? []).map((n) => ({
    id: n.id,
    type: n.type,
    position: n.position ?? { x: 0, y: 0 },
    data: {
      label: n.data?.label,
      config: n.data?.config ?? {},
    },
  }));

  const edges: FlowEdge[] = (graph.edges ?? []).map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? undefined,
    targetHandle: e.targetHandle ?? undefined,
  }));

  return { nodes, edges };
}

/**
 * React Flow → backend graph. We strip RF-internal fields (selected, dragging,
 * measured, …) and emit only the contract shape. `node.type` is required by the
 * backend registry, so we assert it back onto the wire node.
 */
export function flowToGraph(nodes: FlowNode[], edges: FlowEdge[]): WorkflowGraph {
  const wireNodes: GraphNode[] = nodes.map((n) => ({
    id: n.id,
    type: n.type ?? "unknown",
    position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    data: {
      label: n.data.label,
      config: n.data.config ?? {},
    },
  }));

  const wireEdges: GraphEdge[] = edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? null,
    targetHandle: e.targetHandle ?? null,
  }));

  return { nodes: wireNodes, edges: wireEdges };
}

/* ------------------------------------------------------------- misc helpers */

/** Stable-ish unique id for a freshly-dropped node. */
export function newNodeId(type: string): string {
  const rand = Math.random().toString(36).slice(2, 8);
  return `${type}_${rand}`;
}

/** Human-readable grouping order for the palette. */
export const CATEGORY_ORDER: NodeCategory[] = [
  "TRIGGER",
  "LOGIC",
  "ACTION",
  "AI",
];

export const CATEGORY_LABEL: Record<NodeCategory, string> = {
  TRIGGER: "Triggers",
  LOGIC: "Logic",
  ACTION: "Actions",
  AI: "AI",
};

/** Group node definitions by category, in `CATEGORY_ORDER`. */
export function groupByCategory(
  defs: NodeDefinition[],
): Array<{ category: NodeCategory; defs: NodeDefinition[] }> {
  const map = new Map<NodeCategory, NodeDefinition[]>();
  for (const d of defs) {
    const list = map.get(d.category) ?? [];
    list.push(d);
    map.set(d.category, list);
  }
  return CATEGORY_ORDER.filter((c) => map.has(c)).map((category) => ({
    category,
    defs: map.get(category)!,
  }));
}

/** MIME key used for HTML5 drag-and-drop from the palette to the canvas. */
export const DND_MIME = "application/vantis-node-type";
