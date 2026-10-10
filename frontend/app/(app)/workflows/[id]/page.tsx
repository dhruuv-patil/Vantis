"use client";
import * as React from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Background,
  BackgroundVariant,
  ConnectionLineType,
  Controls,
  MiniMap,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  addEdge,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type NodeTypes,
  type OnSelectionChangeParams,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useTheme } from "next-themes";
import { toast } from "sonner";
import {
  fetchNodeTypes,
  fetchWorkflow,
  getErrorMessage,
  publishWorkflow,
  runWorkflow,
  saveWorkflowGraph,
  updateWorkflow,
  validateWorkflow,
  deleteWorkflow,
} from "@/lib/api";
import type {
  GenerateWorkflowResult,
  NodeDefinition,
  ValidationResult,
  WorkflowDetail,
} from "@/types";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  DND_MIME,
  ErrorNodesContext,
  flowToGraph,
  graphToFlow,
  newNodeId,
  type FlowEdge,
  type FlowNode as FlowNodeType,
} from "@/components/app/builder/shared";
import { WorkflowHeader } from "@/components/app/workflow-header";
import { WorkflowExecutionsView } from "@/components/app/workflow-executions-view";
import { WorkflowAnomaliesView } from "@/components/app/workflow-anomalies-view";
import { FlowNode, NodeDefsContext } from "@/components/app/builder/flow-node";
import { NodePalette } from "@/components/app/builder/node-palette";
import { ConfigPanel } from "@/components/app/builder/config-panel";
import { Toolbar } from "@/components/app/builder/toolbar";
import { ValidationPanel } from "@/components/app/builder/validation-panel";
import { PublishDialog } from "@/components/app/builder/publish-dialog";
import { GenerateWorkflowDialog } from "@/components/app/builder/generate-workflow-dialog";
import { WebhookDialog } from "@/components/app/builder/webhook-dialog";
import { hasRole, useAuthStore } from "@/lib/auth-store";

const LAYOUT = {
  nodeWidth: 180,
  nodeHeight: 84,
  horizontalGap: 120,
  verticalGap: 56,
  componentGap: 80,
  topPadding: 60,
  leftPadding: 60,
};

const LANE = {
  height: 8,
  gap: 16,
};

function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function nodeSize(node: FlowNodeType): { width: number; height: number } {
  const width = node.measured?.width ?? node.width ?? LAYOUT.nodeWidth;
  const height = node.measured?.height ?? node.height ?? LAYOUT.nodeHeight;

  return {
    width: Math.max(1, width),
    height: Math.max(1, height),
  };
}

function layoutWorkflow(
  nodes: FlowNodeType[],
  edges: FlowEdge[],
): FlowNodeType[] {
  if (nodes.length === 0) {
    return [];
  }

  const nodeById = new Map<string, FlowNodeType>();
  const sizeById = new Map<string, { width: number; height: number }>();

  for (const node of nodes) {
    nodeById.set(node.id, node);
    sizeById.set(node.id, nodeSize(node));
  }

  const labelOf = (id: string): string =>
    String(nodeById.get(id)?.data?.label ?? id);

  const compareIds = (a: string, b: string): number =>
    compareText(labelOf(a), labelOf(b)) || compareText(a, b);

  const pairKey = (a: string, b: string): string => `${a}\u0000${b}`;

  const pairs: Array<[string, string]> = [];
  const seenPairs = new Set<string>();

  for (const edge of edges) {
    if (
      edge.source === edge.target ||
      !nodeById.has(edge.source) ||
      !nodeById.has(edge.target)
    ) {
      continue;
    }

    const key = pairKey(edge.source, edge.target);

    if (seenPairs.has(key)) {
      continue;
    }

    seenPairs.add(key);
    pairs.push([edge.source, edge.target]);
  }

  const undirected = new Map<string, string[]>();

  for (const node of nodes) {
    undirected.set(node.id, []);
  }

  for (const [source, target] of pairs) {
    undirected.get(source)!.push(target);
    undirected.get(target)!.push(source);
  }

  const visited = new Set<string>();
  const components: string[][] = [];

  for (const node of nodes) {
    if (visited.has(node.id)) {
      continue;
    }

    const members: string[] = [];
    const stack = [node.id];

    visited.add(node.id);

    while (stack.length > 0) {
      const current = stack.pop()!;

      members.push(current);

      for (const next of undirected.get(current) ?? []) {
        if (visited.has(next)) {
          continue;
        }

        visited.add(next);
        stack.push(next);
      }
    }

    members.sort(compareIds);
    components.push(members);
  }

  components.sort(
    (a, b) => b.length - a.length || compareIds(a[0], b[0]),
  );

  const componentIndex = new Map<string, number>();

  components.forEach((members, index) => {
    for (const id of members) {
      componentIndex.set(id, index);
    }
  });

  const pairsByComponent: Array<Array<[string, string]>> = components.map(
    () => [],
  );

  for (const pair of pairs) {
    pairsByComponent[componentIndex.get(pair[0])!].push(pair);
  }

  const layoutComponent = (
    ids: string[],
    componentPairs: Array<[string, string]>,
  ): {
    positions: Map<string, { x: number; y: number }>;
    height: number;
  } => {
    const succ = new Map<string, string[]>();
    const pred = new Map<string, string[]>();

    for (const id of ids) {
      succ.set(id, []);
      pred.set(id, []);
    }

    for (const [source, target] of componentPairs) {
      succ.get(source)!.push(target);
      pred.get(target)!.push(source);
    }

    const color = new Map<string, number>();
    const reversed = new Set<string>();

    const visit = (id: string) => {
      color.set(id, 1);

      for (const next of succ.get(id)!) {
        const state = color.get(next) ?? 0;

        if (state === 1) {
          reversed.add(pairKey(id, next));
        } else if (state === 0) {
          visit(next);
        }
      }

      color.set(id, 2);
    };

    const startOrder = [
      ...ids.filter((id) => pred.get(id)!.length === 0),
      ...ids.filter((id) => pred.get(id)!.length > 0),
    ];

    for (const id of startOrder) {
      if (!color.has(id)) {
        visit(id);
      }
    }

    const dagSucc = new Map<string, string[]>();
    const dagPred = new Map<string, string[]>();

    for (const id of ids) {
      dagSucc.set(id, []);
      dagPred.set(id, []);
    }

    const dagSeen = new Set<string>();

    for (const [source, target] of componentPairs) {
      const isReversed = reversed.has(pairKey(source, target));
      const from = isReversed ? target : source;
      const to = isReversed ? source : target;
      const key = pairKey(from, to);

      if (dagSeen.has(key)) {
        continue;
      }

      dagSeen.add(key);
      dagSucc.get(from)!.push(to);
      dagPred.get(to)!.push(from);
    }

    const layerOf = new Map<string, number>();
    const indegree = new Map<string, number>();

    for (const id of ids) {
      layerOf.set(id, 0);
      indegree.set(id, dagPred.get(id)!.length);
    }

    const topo: string[] = [];
    const queue = ids.filter((id) => indegree.get(id) === 0);

    for (let head = 0; head < queue.length; head++) {
      const current = queue[head];

      topo.push(current);

      for (const next of dagSucc.get(current)!) {
        layerOf.set(
          next,
          Math.max(layerOf.get(next)!, layerOf.get(current)! + 1),
        );

        const remaining = indegree.get(next)! - 1;

        indegree.set(next, remaining);

        if (remaining === 0) {
          queue.push(next);
        }
      }
    }

    if (topo.length < ids.length) {
      const inTopo = new Set(topo);

      for (const id of ids) {
        if (!inTopo.has(id)) {
          topo.push(id);
        }
      }
    }

    for (let i = topo.length - 1; i >= 0; i--) {
      const id = topo[i];
      const outs = dagSucc.get(id)!;

      if (dagPred.get(id)!.length === 0 && outs.length > 0) {
        let earliest = Infinity;

        for (const next of outs) {
          earliest = Math.min(earliest, layerOf.get(next)!);
        }

        if (Number.isFinite(earliest) && earliest - 1 > layerOf.get(id)!) {
          layerOf.set(id, earliest - 1);
        }
      }
    }

    const aLayer = new Map<string, number>();
    const aHeight = new Map<string, number>();
    const aReal = new Set<string>();
    const aSucc = new Map<string, string[]>();
    const aPred = new Map<string, string[]>();

    const addAug = (
      id: string,
      layer: number,
      height: number,
      real: boolean,
    ) => {
      aLayer.set(id, layer);
      aHeight.set(id, height);
      aSucc.set(id, []);
      aPred.set(id, []);

      if (real) {
        aReal.add(id);
      }
    };

    const link = (from: string, to: string) => {
      aSucc.get(from)!.push(to);
      aPred.get(to)!.push(from);
    };

    for (const id of ids) {
      addAug(id, layerOf.get(id)!, sizeById.get(id)!.height, true);
    }

    let laneCount = 0;

    for (const from of ids) {
      for (const to of dagSucc.get(from)!) {
        const fromLayer = layerOf.get(from)!;
        const toLayer = layerOf.get(to)!;

        if (toLayer - fromLayer < 1) {
          continue;
        }

        let previous = from;

        for (let l = fromLayer + 1; l < toLayer; l++) {
          const laneId = `\u0000lane:${laneCount++}`;

          addAug(laneId, l, LANE.height, false);
          link(previous, laneId);
          previous = laneId;
        }

        link(previous, to);
      }
    }

    let layerCount = 1;

    for (const layer of aLayer.values()) {
      layerCount = Math.max(layerCount, layer + 1);
    }

    const rank = new Map<string, number>();

    const order = (id: string) => {
      rank.set(id, rank.size);

      for (const next of aSucc.get(id)!) {
        if (!rank.has(next)) {
          order(next);
        }
      }
    };

    for (const id of ids) {
      if (aPred.get(id)!.length === 0 && !rank.has(id)) {
        order(id);
      }
    }

    for (const id of aLayer.keys()) {
      if (!rank.has(id)) {
        order(id);
      }
    }

    const layers: string[][] = Array.from({ length: layerCount }, () => []);

    for (const [id, layer] of aLayer) {
      layers[layer].push(id);
    }

    for (const layer of layers) {
      layer.sort((a, b) => rank.get(a)! - rank.get(b)!);
    }

    const pos = new Map<string, number>();

    const refreshPositions = () => {
      for (const layer of layers) {
        layer.forEach((id, index) => pos.set(id, index));
      }
    };

    refreshPositions();

    const countCrossings = (): number => {
      let total = 0;

      for (let l = 0; l < layerCount - 1; l++) {
        const segments: Array<[number, number]> = [];

        for (const id of layers[l]) {
          for (const next of aSucc.get(id)!) {
            segments.push([pos.get(id)!, pos.get(next)!]);
          }
        }

        for (let i = 0; i < segments.length; i++) {
          for (let j = i + 1; j < segments.length; j++) {
            if (
              (segments[i][0] - segments[j][0]) *
                (segments[i][1] - segments[j][1]) <
              0
            ) {
              total++;
            }
          }
        }
      }

      return total;
    };

    const sweep = (down: boolean) => {
      const neighborMap = down ? aPred : aSucc;

      const start = down ? 1 : layerCount - 2;
      const end = down ? layerCount : -1;
      const step = down ? 1 : -1;

      for (let l = start; l !== end; l += step) {
        const keyed = layers[l].map((id, index) => {
          const ns = neighborMap.get(id)!;

          let bary = index;

          if (ns.length > 0) {
            let sum = 0;

            for (const n of ns) {
              sum += pos.get(n)!;
            }

            bary = sum / ns.length;
          }

          return { id, bary, index };
        });

        keyed.sort((a, b) => a.bary - b.bary || a.index - b.index);

        layers[l] = keyed.map((entry) => entry.id);

        layers[l].forEach((id, index) => pos.set(id, index));
      }
    };

    let bestCrossings = countCrossings();
    let bestLayers = layers.map((layer) => [...layer]);

    for (
      let iteration = 0;
      iteration < 16 && bestCrossings > 0;
      iteration++
    ) {
      for (const down of [true, false]) {
        sweep(down);

        const crossings = countCrossings();

        if (crossings < bestCrossings) {
          bestCrossings = crossings;
          bestLayers = layers.map((layer) => [...layer]);
        }
      }
    }

    bestLayers.forEach((layer, index) => {
      layers[index] = layer;
    });

    refreshPositions();

    const separation = (a: string, b: string): number =>
      (aHeight.get(a)! + aHeight.get(b)!) / 2 +
      (aReal.has(a) && aReal.has(b) ? LAYOUT.verticalGap : LANE.gap);

    const y = new Map<string, number>();

    for (const layer of layers) {
      const offsets: number[] = [];
      let cursor = 0;

      layer.forEach((id, index) => {
        if (index > 0) {
          cursor += separation(layer[index - 1], id);
        }

        offsets.push(cursor);
      });

      layer.forEach((id, index) => {
        y.set(id, offsets[index] - cursor / 2);
      });
    }

    const resolveLayer = (layer: string[], desired: number[]) => {
      const count = layer.length;

      if (count === 0) {
        return;
      }

      const offsets: number[] = [0];

      for (let i = 1; i < count; i++) {
        offsets.push(
          offsets[i - 1] +
            separation(layer[i - 1], layer[i]),
        );
      }

      const blocks: Array<{
        sum: number;
        count: number;
        start: number;
      }> = [];

      for (let i = 0; i < count; i++) {
        blocks.push({
          sum: desired[i] - offsets[i],
          count: 1,
          start: i,
        });

        while (blocks.length > 1) {
          const last = blocks[blocks.length - 1];
          const previous = blocks[blocks.length - 2];

          if (
            previous.sum / previous.count <=
            last.sum / last.count
          ) {
            break;
          }

          previous.sum += last.sum;
          previous.count += last.count;

          blocks.pop();
        }
      }

      for (const block of blocks) {
        const mean = block.sum / block.count;

        for (
          let k = block.start;
          k < block.start + block.count;
          k++
        ) {
          y.set(layer[k], mean + offsets[k]);
        }
      }
    };
        const relax = (l: number) => {
      const layer = layers[l];

      const desired = layer.map((id) => {
        const ns = [
          ...aPred.get(id)!,
          ...aSucc.get(id)!,
        ];

        if (ns.length === 0) {
          return y.get(id)!;
        }

        let sum = 0;

        for (const n of ns) {
          sum += y.get(n)!;
        }

        return sum / ns.length;
      });

      resolveLayer(layer, desired);
    };

    for (let iteration = 0; iteration < 30; iteration++) {
      for (let l = 0; l < layerCount; l++) {
        relax(l);
      }

      for (let l = layerCount - 1; l >= 0; l--) {
        relax(l);
      }
    }

    const layerWidths = layers.map((layer) =>
      layer.reduce(
        (width, id) =>
          aReal.has(id)
            ? Math.max(
                width,
                sizeById.get(id)!.width,
              )
            : width,
        0,
      ),
    );

    const layerX: number[] = [];

    let cursorX = 0;

    layerWidths.forEach((width, index) => {
      layerX[index] = cursorX;
      cursorX += width + LAYOUT.horizontalGap;
    });

    let minTop = Infinity;
    let maxBottom = -Infinity;

    for (const [id, centerY] of y) {
      const height = aHeight.get(id)!;

      minTop = Math.min(
        minTop,
        centerY - height / 2,
      );

      maxBottom = Math.max(
        maxBottom,
        centerY + height / 2,
      );
    }

    const positions = new Map<
      string,
      { x: number; y: number }
    >();

    for (const id of ids) {
      positions.set(id, {
        x: Math.round(
          layerX[aLayer.get(id)!],
        ),
        y: Math.round(
          y.get(id)! -
            aHeight.get(id)! / 2 -
            minTop,
        ),
      });
    }

    return {
      positions,
      height: maxBottom - minTop,
    };
  };

  const positions = new Map<
    string,
    { x: number; y: number }
  >();

  let cursorY = LAYOUT.topPadding;

  components.forEach((members, index) => {
    const result = layoutComponent(
      members,
      pairsByComponent[index],
    );

    for (const [id, position] of result.positions) {
      positions.set(id, {
        x:
          LAYOUT.leftPadding +
          position.x,
        y: Math.round(
          cursorY + position.y,
        ),
      });
    }

    cursorY +=
      result.height +
      LAYOUT.componentGap;
  });

  return nodes.map((node) => ({
    ...node,
    position:
      positions.get(node.id) ?? {
        x: LAYOUT.leftPadding,
        y: LAYOUT.topPadding,
      },
  }));
}

function layoutWorkflowEdges(
  nodes: FlowNodeType[],
  edges: FlowEdge[],
): FlowEdge[] {
  if (edges.length === 0) {
    return [];
  }

  const nodeById = new Map<
    string,
    FlowNodeType
  >();

  for (const node of nodes) {
    nodeById.set(node.id, node);
  }

  return edges.map((edge) => {
    const source = nodeById.get(edge.source);
    const target = nodeById.get(edge.target);

    if (!source || !target) {
      return {
        ...edge,
        type: "default",
        animated: false,
      };
    }

    return {
      ...edge,
      type: "default",
      animated: false,
    };
  });
}

function shouldAutoLayout(
  nodes: FlowNodeType[],
  laidOut: FlowNodeType[],
): boolean {
  if (nodes.length < 2) {
    return false;
  }

  for (const node of nodes) {
    if (
      !Number.isFinite(node.position?.x) ||
      !Number.isFinite(node.position?.y)
    ) {
      return true;
    }
  }

  for (
    let i = 0;
    i < nodes.length;
    i++
  ) {
    for (
      let j = i + 1;
      j < nodes.length;
      j++
    ) {
      if (
        Math.abs(
          nodes[i].position.x -
            nodes[j].position.x,
        ) < LAYOUT.nodeWidth &&
        Math.abs(
          nodes[i].position.y -
            nodes[j].position.y,
        ) < LAYOUT.nodeHeight
      ) {
        return true;
      }
    }
  }

  const boxArea = (
    list: FlowNodeType[],
  ): number => {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    for (const node of list) {
      minX = Math.min(
        minX,
        node.position.x,
      );

      minY = Math.min(
        minY,
        node.position.y,
      );

      maxX = Math.max(
        maxX,
        node.position.x +
          LAYOUT.nodeWidth,
      );

      maxY = Math.max(
        maxY,
        node.position.y +
          LAYOUT.nodeHeight,
      );
    }

    return (
      (maxX - minX) *
      (maxY - minY)
    );
  };

  return (
    boxArea(nodes) >
    boxArea(laidOut) * 3
  );
}

function prepareInitialFlow(
  nodes: FlowNodeType[],
  edges: FlowEdge[],
): {
  nodes: FlowNodeType[];
  edges: FlowEdge[];
} {
  const laidOut = layoutWorkflow(
    nodes,
    edges,
  );

  if (!shouldAutoLayout(nodes, laidOut)) {
    return {
      nodes,
      edges,
    };
  }

  return {
    nodes: laidOut,
    edges: layoutWorkflowEdges(
      laidOut,
      edges,
    ),
  };
}

export default function WorkflowDetailPage() {
  const params =
    useParams<{ id: string }>();

  const id = params.id;

  const queryClient =
    useQueryClient();

  const router = useRouter();

  const [activeView, setActiveView] =
    React.useState<
      "editor" | "executions" | "anomalies"
    >("editor");

  const [
    builderHeaderState,
    setBuilderHeaderState,
  ] =
    React.useState<BuilderHeaderState>({
      dirty: false,
      savePending: false,
      publishPending: false,
      validatePending: false,
      hasWebhookTrigger: false,
    });

  const builderRef =
    React.useRef<BuilderHandle>(null);

  const deleteMutation =
    useMutation({
      mutationFn: () =>
        deleteWorkflow(id),

      onSuccess: () => {
        queryClient.removeQueries({
          queryKey: ["workflow", id],
        });

        toast.success(
          "Workflow deleted.",
        );

        router.push(
          "/workflows",
        );
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(err),
        ),
    });

  const workflowQuery = useQuery({
    queryKey: ["workflow", id],
    queryFn: () =>
      fetchWorkflow(id),
    enabled: Boolean(id),
  });

  const nodeTypesQuery = useQuery({
    queryKey: ["node-types"],
    queryFn: fetchNodeTypes,
    staleTime:
      5 * 60 * 1000,
  });

  if (
    workflowQuery.isPending
  ) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-[70vh] w-full" />
      </div>
    );
  }

  if (
    workflowQuery.isError
  ) {
    return (
      <div className="flex h-[60vh] flex-col items-center justify-center gap-3 text-center">
        <p className="text-sm text-destructive">
          {getErrorMessage(
            workflowQuery.error,
          )}
        </p>

        <Button
          variant="outline"
          onClick={() =>
            workflowQuery.refetch()
          }
        >
          Retry
        </Button>
      </div>
    );
  }

  const workflow =
    workflowQuery.data;

  const current =
    queryClient.getQueryData<WorkflowDetail>(
      [
        "workflow",
        workflow.id,
      ],
    );

  const status =
    current?.status ??
    workflow.status;

  const version =
    current?.latestVersion ??
    workflow.latestVersion;

  const name =
    current?.name ??
    workflow.name;

  return (
    <div className="flex h-screen flex-col bg-background text-foreground dark:bg-[#050505] dark:text-white">
      <WorkflowHeader
        workflow={workflow}
        version={version}
        dirty={
          builderHeaderState.dirty
        }
        canRun={
          version != null
        }
        namePending={false}
        onRename={() => {}}
        onValidate={() => {
          if (
            activeView !==
            "editor"
          ) {
            setActiveView(
              "editor",
            );

            window.setTimeout(
              () =>
                builderRef.current?.validate(),
              0,
            );

            return;
          }

          builderRef.current?.validate();
        }}
        validatePending={
          builderHeaderState.validatePending
        }
        onGenerate={() => {
          if (
            activeView !==
            "editor"
          ) {
            setActiveView(
              "editor",
            );

            window.setTimeout(
              () =>
                builderRef.current?.generate(),
              0,
            );

            return;
          }

          builderRef.current?.generate();
        }}
        onSave={() =>
          builderRef.current?.save()
        }
        onPublish={() => {
          if (
            activeView !==
            "editor"
          ) {
            setActiveView(
              "editor",
            );

            window.setTimeout(
              () =>
                builderRef.current?.publish(),
              0,
            );

            return;
          }

          builderRef.current?.publish();
        }}
        onDelete={() => {
          if (
            deleteMutation.isPending
          ) {
            return;
          }

          const confirmed =
            window.confirm(
              `Delete "${workflow.name}"? This cannot be undone.`,
            );

          if (confirmed) {
            deleteMutation.mutate();
          }
        }}
        savePending={
          builderHeaderState.savePending
        }
        publishPending={
          builderHeaderState.publishPending
        }
        deletePending={
          deleteMutation.isPending
        }
        onWebhook={() => {
          if (
            activeView !==
            "editor"
          ) {
            setActiveView(
              "editor",
            );

            window.setTimeout(
              () =>
                builderRef.current?.webhook(),
              0,
            );

            return;
          }

          builderRef.current?.webhook();
        }}
        hasWebhookTrigger={
          builderHeaderState.hasWebhookTrigger
        }
        activeView={activeView}
        onViewChange={
          setActiveView
        }
      />

      <div className="flex-1 min-h-0 overflow-hidden bg-background">
        {activeView ===
        "editor" ? (
          <ReactFlowProvider>
            <Builder
              ref={builderRef}
              workflow={workflow}
              nodeDefs={
                nodeTypesQuery.data
                  ?.nodeTypes ?? []
              }
              nodeDefsLoading={
                nodeTypesQuery.isPending
              }
              nodeDefsError={
                nodeTypesQuery.isError
              }
              showToolbar={false}
              onHeaderStateChange={
                setBuilderHeaderState
              }
            />
          </ReactFlowProvider>
        ) : activeView ===
          "executions" ? (
          <div className="px-2 lg:px-3 pt-3">
            <WorkflowExecutionsView
              workflowId={
                workflow.id
              }
            />
          </div>
        ) : (
          <div className="px-2 lg:px-3 pt-3">
            <WorkflowAnomaliesView
              workflowId={
                workflow.id
              }
            />
          </div>
        )}
      </div>
    </div>
  );
}

interface BuilderHeaderState {
  dirty: boolean;
  savePending: boolean;
  publishPending: boolean;
  validatePending: boolean;
  hasWebhookTrigger: boolean;
}

interface BuilderHandle {
  save: () => void;
  publish: () => void;
  generate: () => void;
  webhook: () => void;
  validate: () => void;
}

interface BuilderProps {
  workflow: WorkflowDetail;
  nodeDefs: NodeDefinition[];
  nodeDefsLoading: boolean;
  nodeDefsError: boolean;
  showToolbar?: boolean;
  onHeaderStateChange?: (
    state: BuilderHeaderState,
  ) => void;
}

const Builder =
  React.forwardRef<
    BuilderHandle,
    BuilderProps
  >(function Builder(
    {
      workflow,
      nodeDefs,
      nodeDefsLoading,
      nodeDefsError,
      showToolbar = true,
      onHeaderStateChange,
    },
    ref,
  ) {
    const queryClient =
      useQueryClient();

    const router =
      useRouter();

    const rf =
      useReactFlow<
        FlowNodeType,
        FlowEdge
      >();

    const { resolvedTheme } = useTheme();
    const isDark = resolvedTheme !== "light";

    const wrapperRef =
      React.useRef<HTMLDivElement>(
        null,
      );

    const defsByType =
      React.useMemo(() => {
        const map: Record<
          string,
          NodeDefinition
        > = {};

        for (const d of nodeDefs) {
          map[d.type] = d;
        }

        return map;
      }, [nodeDefs]);

    const nodeTypes =
      React.useMemo<NodeTypes>(
        () => {
          const map: NodeTypes =
            {};

          for (
            const d of nodeDefs
          ) {
            map[d.type] =
              FlowNode;
          }

          return map;
        },
        [nodeDefs],
      );

    const initial =
      React.useMemo(() => {
        const flow =
          graphToFlow(
            workflow.graph,
          );

        const arranged =
          prepareInitialFlow(
            flow.nodes,
            flow.edges,
          );

        return {
          original: flow,
          nodes: arranged.nodes,
          edges: arranged.edges,
        };
      }, [workflow.graph]);

    const [
      nodes,
      setNodes,
      onNodesChange,
    ] =
      useNodesState<FlowNodeType>(
        initial.nodes,
      );

    const [
      edges,
      setEdges,
      onEdgesChange,
    ] =
      useEdgesState<FlowEdge>(
        initial.edges,
      );

    const [
      selectedId,
      setSelectedId,
    ] =
      React.useState<
        string | null
      >(null);

    const savedRef =
      React.useRef<string>(
        JSON.stringify(
          flowToGraph(
            initial.original.nodes,
            initial.original.edges,
          ),
        ),
      );

    const [dirty, setDirty] =
      React.useState(false);

    const recomputeDirty =
      React.useCallback(
        (
          nextNodes: FlowNodeType[],
          nextEdges: FlowEdge[],
        ) => {
          const now =
            JSON.stringify(
              flowToGraph(
                nextNodes,
                nextEdges,
              ),
            );

          setDirty(
            now !==
              savedRef.current,
          );
        },
        [],
      );

    React.useEffect(() => {
      recomputeDirty(
        nodes,
        edges,
      );
    }, [
      nodes,
      edges,
      recomputeDirty,
    ]);

    interface Snapshot {
      nodes: FlowNodeType[];
      edges: FlowEdge[];
    }

    const past =
      React.useRef<
        Snapshot[]
      >([]);

    const future =
      React.useRef<
        Snapshot[]
      >([]);

    const [, setHistTick] =
      React.useState(0);

    const snapshot =
      React.useCallback(
        (): Snapshot => ({
          nodes:
            structuredClone(
              rf.getNodes(),
            ),
          edges:
            structuredClone(
              rf.getEdges(),
            ),
        }),
        [rf],
      );

    const commit =
      React.useCallback(() => {
        past.current.push(
          snapshot(),
        );

        if (
          past.current.length >
          100
        ) {
          past.current.shift();
        }

        future.current = [];

        setHistTick(
          (t) => t + 1,
        );
      }, [snapshot]);

    const undo =
      React.useCallback(
        () => {
          const prev =
            past.current.pop();

          if (!prev) return;

          future.current.push(
            snapshot(),
          );

          setNodes(
            prev.nodes,
          );

          setEdges(
            prev.edges,
          );

          setHistTick(
            (t) => t + 1,
          );
        },
        [
          snapshot,
          setNodes,
          setEdges,
        ],
      );

    const redo =
      React.useCallback(
        () => {
          const next =
            future.current.pop();

          if (!next) return;

          past.current.push(
            snapshot(),
          );

          setNodes(
            next.nodes,
          );

          setEdges(
            next.edges,
          );

          setHistTick(
            (t) => t + 1,
          );
        },
        [
          snapshot,
          setNodes,
          setEdges,
        ],
      );
          const onConnect =
      React.useCallback(
        (conn: Connection) => {
          commit();

          setEdges((eds) =>
            addEdge(
              {
                ...conn,
                type: "default",
                animated: false,
              },
              eds,
            ),
          );
        },
        [commit, setEdges],
      );

    const addNode =
      React.useCallback(
        (
          type: string,
          screenPos?: {
            x: number;
            y: number;
          },
        ) => {
          const def =
            defsByType[type];

          if (!def) return;

          const bounds =
            wrapperRef.current?.getBoundingClientRect();

          let position: {
            x: number;
            y: number;
          };

          if (
            screenPos &&
            bounds
          ) {
            position =
              rf.screenToFlowPosition({
                x: screenPos.x,
                y: screenPos.y,
              });
          } else if (
            nodes.length > 0
          ) {
            const rightmost =
              nodes.reduce(
                (
                  current,
                  node,
                ) =>
                  node.position.x >
                  current.position.x
                    ? node
                    : current,
                nodes[0],
              );

            position = {
              x:
                rightmost.position.x +
                LAYOUT.nodeWidth +
                LAYOUT.horizontalGap,
              y:
                rightmost.position.y,
            };
          } else {
            const center =
              rf.screenToFlowPosition({
                x:
                  (bounds?.left ??
                    0) +
                  (bounds?.width ??
                    800) /
                    2,
                y:
                  (bounds?.top ??
                    0) +
                  (bounds?.height ??
                    600) /
                    2,
              });

            position = {
              x:
                center.x -
                LAYOUT.nodeWidth /
                  2,
              y:
                center.y -
                LAYOUT.nodeHeight /
                  2,
            };
          }

          const node: FlowNodeType =
            {
              id: newNodeId(
                type,
              ),
              type,
              position,
              data: {
                label: def.label,
                config: {},
              },
            };

          commit();

          setNodes((nds) =>
            nds.concat(node),
          );

          setSelectedId(
            node.id,
          );
        },
        [
          defsByType,
          nodes,
          rf,
          commit,
          setNodes,
        ],
      );

    const onDrop =
      React.useCallback(
        (event: React.DragEvent) => {
          event.preventDefault();

          const type =
            event.dataTransfer.getData(
              DND_MIME,
            );

          if (!type) return;

          addNode(type, {
            x: event.clientX,
            y: event.clientY,
          });
        },
        [addNode],
      );

    const onDragOver =
      React.useCallback(
        (event: React.DragEvent) => {
          event.preventDefault();

          event.dataTransfer.dropEffect =
            "move";
        },
        [],
      );

    const autoLayout =
      React.useCallback(
        () => {
          const currentNodes =
            rf.getNodes();

          const currentEdges =
            rf.getEdges();

          if (
            currentNodes.length ===
            0
          ) {
            return;
          }

          const laidOutNodes =
            layoutWorkflow(
              currentNodes,
              currentEdges,
            );

          const laidOutEdges =
            layoutWorkflowEdges(
              laidOutNodes,
              currentEdges,
            );

          commit();

          setNodes(
            laidOutNodes,
          );

          setEdges(
            laidOutEdges,
          );

          window.requestAnimationFrame(
            () => {
              window.requestAnimationFrame(
                () => {
                  rf.fitView({
                    duration: 350,
                    padding: 0.15,
                    maxZoom: 1.1,
                  });
                },
              );
            },
          );
        },
        [
          rf,
          commit,
          setNodes,
          setEdges,
        ],
      );

    const onSelectionChange =
      React.useCallback(
        ({
          nodes: selected,
        }: OnSelectionChangeParams) => {
          setSelectedId(
            selected.length ===
              1
              ? selected[0].id
              : null,
          );
        },
        [],
      );

    const onBeforeDelete =
      React.useCallback(
        async () => {
          commit();

          return true;
        },
        [commit],
      );

    const onNodeDragStart =
      React.useCallback(
        () => {
          commit();
        },
        [commit],
      );

    const selectedNode =
      React.useMemo(
        () =>
          nodes.find(
            (n) =>
              n.id ===
              selectedId,
          ) ?? null,
        [
          nodes,
          selectedId,
        ],
      );

    const updateSelected =
      React.useCallback(
        (
          updater: (
            data: FlowNodeType["data"],
          ) =>
            FlowNodeType["data"],
        ) => {
          if (!selectedId) {
            return;
          }

          setNodes((nds) =>
            nds.map((n) =>
              n.id ===
              selectedId
                ? {
                    ...n,
                    data: updater(
                      n.data,
                    ),
                  }
                : n,
            ),
          );
        },
        [
          selectedId,
          setNodes,
        ],
      );

    const onLabelChange =
      React.useCallback(
        (label: string) =>
          updateSelected(
            (data) => ({
              ...data,
              label,
            }),
          ),
        [updateSelected],
      );

    const onConfigChange =
      React.useCallback(
        (
          key: string,
          value: unknown,
        ) =>
          updateSelected(
            (data) => ({
              ...data,
              config: {
                ...(data.config ??
                  {}),
                [key]: value,
              },
            }),
          ),
        [updateSelected],
      );

    const upstreamIds =
      React.useMemo(() => {
        if (!selectedId) {
          return [];
        }

        return edges
          .filter(
            (e) =>
              e.target ===
              selectedId,
          )
          .map(
            (e) =>
              e.source,
          )
          .filter(
            (v, i, a) =>
              a.indexOf(v) ===
              i,
          );
      }, [
        edges,
        selectedId,
      ]);

    const [
      validation,
      setValidation,
    ] =
      React.useState<ValidationResult | null>(
        null,
      );

    const [
      showValidation,
      setShowValidation,
    ] = React.useState(false);

    const errorNodeIds =
      React.useMemo(() => {
        const set =
          new Set<string>();

        for (
          const issue of
            validation?.issues ??
            []
        ) {
          if (
            issue.severity ===
              "ERROR" &&
            issue.nodeId
          ) {
            set.add(
              issue.nodeId,
            );
          }
        }

        return set;
      }, [validation]);

    const focusNode =
      React.useCallback(
        (nodeId: string) => {
          const node =
            rf.getNode(
              nodeId,
            );

          if (!node) {
            return;
          }

          setSelectedId(
            nodeId,
          );

          rf.setCenter(
            node.position.x +
              LAYOUT.nodeWidth /
                2,
            node.position.y +
              LAYOUT.nodeHeight /
                2,
            {
              zoom: 1.2,
              duration: 400,
            },
          );
        },
        [rf],
      );

    const saveMutation =
      useMutation({
        mutationFn: () =>
          saveWorkflowGraph(
            workflow.id,
            flowToGraph(
              nodes,
              edges,
            ),
          ),

        onSuccess: (
          detail,
        ) => {
          savedRef.current =
            JSON.stringify(
              detail.graph,
            );

          setDirty(false);

          queryClient.setQueryData(
            [
              "workflow",
              workflow.id,
            ],
            detail,
          );

          toast.success(
            "Workflow saved.",
          );
        },

        onError: (err) =>
          toast.error(
            getErrorMessage(err),
          ),
      });

    const validateMutation =
      useMutation({
        mutationFn: () =>
          validateWorkflow(
            workflow.id,
          ),

        onSuccess: (
          result,
        ) => {
          setValidation(
            result,
          );

          setShowValidation(
            true,
          );

          if (
            result.valid
          ) {
            toast.success(
              "Validation passed.",
            );
          } else {
            const errs =
              result.issues.filter(
                (i) =>
                  i.severity ===
                  "ERROR",
              ).length;

            toast.error(
              `${errs} validation error(s) found.`,
            );
          }
        },

        onError: (err) =>
          toast.error(
            getErrorMessage(err),
          ),
      });

    const [
      publishOpen,
      setPublishOpen,
    ] =
      React.useState(false);

    const [
      publishBlock,
      setPublishBlock,
    ] =
      React.useState<ValidationResult | null>(
        null,
      );

    const publishMutation =
      useMutation({
        mutationFn: (
          note: string,
        ) =>
          publishWorkflow(
            workflow.id,
            note || undefined,
          ),

        onSuccess: (
          result,
        ) => {
          setValidation(
            result.validation,
          );

          if (
            result.published &&
            result.version
          ) {
            setPublishBlock(
              null,
            );

            setPublishOpen(
              false,
            );

            setShowValidation(
              false,
            );

            queryClient.invalidateQueries(
              {
                queryKey: [
                  "workflow",
                  workflow.id,
                ],
              },
            );

            toast.success(
              `Published version v${result.version.versionNumber}.`,
            );
          } else {
            setPublishBlock(
              result.validation,
            );

            setShowValidation(
              true,
            );

            toast.error(
              "Publish blocked by validation errors.",
            );
          }
        },

        onError: (err) =>
          toast.error(
            getErrorMessage(err),
          ),
      });

    const renameMutation =
      useMutation({
        mutationFn: (
          name: string,
        ) =>
          updateWorkflow(
            workflow.id,
            {
              name,
              description:
                workflow.description ??
                undefined,
            },
          ),

        onSuccess: (
          detail,
        ) => {
          queryClient.setQueryData(
            [
              "workflow",
              workflow.id,
            ],
            detail,
          );

          toast.success(
            "Workflow renamed.",
          );
        },

        onError: (err) =>
          toast.error(
            getErrorMessage(err),
          ),
      });

    const runMutation =
      useMutation({
        mutationFn: () =>
          runWorkflow(
            workflow.id,
          ),

        onSuccess: (
          execution,
        ) => {
          toast.success(
            "Run started.",
          );

          router.push(
            `/executions/${execution.id}`,
          );
        },

        onError: (err) =>
          toast.error(
            getErrorMessage(err),
          ),
      });

    const [isValidating, setIsValidating] =
      React.useState(false);

    const doValidate =
      React.useCallback(async () => {
        if (
          validateMutation.isPending ||
          isValidating ||
          saveMutation.isPending
        ) {
          return;
        }

        setIsValidating(true);
        try {
          if (dirty) {
            const detail =
              await saveWorkflowGraph(
                workflow.id,
                flowToGraph(
                  nodes,
                  edges,
                ),
              );
            savedRef.current =
              JSON.stringify(
                detail.graph,
              );
            setDirty(false);
            queryClient.setQueryData(
              [
                "workflow",
                workflow.id,
              ],
              detail,
            );
          }
          await validateMutation.mutateAsync();
        } catch (err) {
          if (dirty) {
            toast.error(
              "Failed to save draft before validation: " +
                getErrorMessage(err),
            );
          }
        } finally {
          setIsValidating(false);
        }
      }, [
        dirty,
        nodes,
        edges,
        workflow.id,
        queryClient,
        saveMutation.isPending,
        validateMutation,
        isValidating,
      ]);

    const doSave =
      React.useCallback(
        () => {
          if (
            !saveMutation.isPending
          ) {
            saveMutation.mutate();
          }
        },
        [saveMutation],
      );

    React.useImperativeHandle(
      ref,
      () => ({
        save: () =>
          doSave(),

        validate: () =>
          doValidate(),

        publish: () => {
          if (
            publishMutation.isPending
          ) {
            return;
          }

          setPublishBlock(
            null,
          );

          setPublishOpen(
            true,
          );
        },

        generate: () =>
          setGenerateOpen(
            true,
          ),

        webhook: () =>
          setWebhookOpen(
            true,
          ),
      }),
      [
        doSave,
        doValidate,
        publishMutation.isPending,
      ],
    );
        const [
      generateOpen,
      setGenerateOpen,
    ] =
      React.useState(false);

    const [
      webhookOpen,
      setWebhookOpen,
    ] =
      React.useState(false);

    const hasWebhookTrigger =
      React.useMemo(
        () =>
          nodes.some(
            (n) =>
              n.type ===
              "webhook_trigger",
          ),
        [nodes],
      );

    React.useEffect(() => {
      onHeaderStateChange?.({
        dirty,
        savePending:
          saveMutation.isPending,
        publishPending:
          publishMutation.isPending,
        validatePending:
          validateMutation.isPending || isValidating,
        hasWebhookTrigger,
      });
    }, [
      dirty,
      saveMutation.isPending,
      publishMutation.isPending,
      validateMutation.isPending,
      isValidating,
      hasWebhookTrigger,
      onHeaderStateChange,
    ]);

    const canManageWebhook =
      hasRole(
        useAuthStore(
          (s) =>
            s.currentRole,
        ),
        "ADMIN",
      );

    const acceptGenerated =
      React.useCallback(
        (
          result: GenerateWorkflowResult,
        ) => {
          const next =
            graphToFlow(
              result.graph,
            );

          const laidOutNodes =
            layoutWorkflow(
              next.nodes,
              next.edges,
            );

          const laidOutEdges =
            layoutWorkflowEdges(
              laidOutNodes,
              next.edges,
            );

          commit();

          setNodes(
            laidOutNodes,
          );

          setEdges(
            laidOutEdges,
          );

          setSelectedId(
            null,
          );

          window.requestAnimationFrame(
            () => {
              window.requestAnimationFrame(
                () => {
                  rf.fitView({
                    duration: 350,
                    padding: 0.15,
                    maxZoom: 1.1,
                  });
                },
              );
            },
          );

          const n =
            laidOutNodes.length;

          toast.success(
            `Added ${n} node${
              n === 1
                ? ""
                : "s"
            }. Review, then save.`,
          );
        },
        [
          commit,
          setNodes,
          setEdges,
          rf,
        ],
      );

    React.useEffect(() => {
      function onKey(
        e: KeyboardEvent,
      ) {
        const target =
          e.target as HTMLElement | null;

        const inField =
          target &&
          (
            target.tagName ===
              "INPUT" ||
            target.tagName ===
              "TEXTAREA" ||
            target.tagName ===
              "SELECT" ||
            target.isContentEditable
          );

        const mod =
          e.metaKey ||
          e.ctrlKey;

        if (
          mod &&
          e.key.toLowerCase() ===
            "s"
        ) {
          e.preventDefault();

          doSave();
        } else if (
          mod &&
          !inField &&
          e.key.toLowerCase() ===
            "z"
        ) {
          e.preventDefault();

          if (e.shiftKey) {
            redo();
          } else {
            undo();
          }
        }
      }

      window.addEventListener(
        "keydown",
        onKey,
      );

      return () =>
        window.removeEventListener(
          "keydown",
          onKey,
        );
    }, [
      doSave,
      undo,
      redo,
    ]);

    const current =
      queryClient.getQueryData<WorkflowDetail>(
        [
          "workflow",
          workflow.id,
        ],
      );

    const status =
      current?.status ??
      workflow.status;

    const version =
      current?.latestVersion ??
      workflow.latestVersion;

    const name =
      current?.name ??
      workflow.name;

    const [
      paletteCollapsed,
      setPaletteCollapsed,
    ] =
      React.useState(false);

    const [
      inspectorOpen,
      setInspectorOpen,
    ] =
      React.useState(true);

    React.useEffect(() => {
      if (selectedId) {
        setInspectorOpen(
          true,
        );
      }
    }, [selectedId]);

    return (
      <ErrorNodesContext.Provider
        value={errorNodeIds}
      >
        <NodeDefsContext.Provider
          value={defsByType}
        >
          <div className="flex h-full flex-col">
            {showToolbar && (
              <Toolbar
                name={name}
                status={status}
                version={version}
                dirty={dirty}
                canUndo={
                  past.current
                    .length > 0
                }
                canRedo={
                  future.current
                    .length > 0
                }
                savePending={
                  saveMutation.isPending
                }
                validatePending={
                  validateMutation.isPending
                }
                namePending={
                  renameMutation.isPending
                }
                canRun={
                  version != null
                }
                runPending={
                  runMutation.isPending
                }
                onRename={(n) =>
                  renameMutation.mutate(
                    n,
                  )
                }
                onUndo={undo}
                onRedo={redo}
                onZoomIn={() =>
                  rf.zoomIn({
                    duration: 200,
                  })
                }
                onZoomOut={() =>
                  rf.zoomOut({
                    duration: 200,
                  })
                }
                onFitView={() =>
                  rf.fitView({
                    duration: 300,
                    padding: 0.15,
                    maxZoom: 1.1,
                  })
                }
                onValidate={doValidate}
                onSave={doSave}
                onPublish={() => {
                  setPublishBlock(
                    null,
                  );

                  setPublishOpen(
                    true,
                  );
                }}
                onRun={() =>
                  runMutation.mutate()
                }
                onGenerate={() =>
                  setGenerateOpen(
                    true,
                  )
                }
                onWebhook={() =>
                  setWebhookOpen(
                    true,
                  )
                }
                hasWebhookTrigger={
                  hasWebhookTrigger
                }
              />
            )}

            <div className="flex min-h-0 flex-1">
              <NodePalette
                defs={nodeDefs}
                collapsed={
                  paletteCollapsed
                }
                onToggle={() =>
                  setPaletteCollapsed(
                    (c) => !c,
                  )
                }
                onAdd={(type) =>
                  addNode(type)
                }
                isLoading={
                  nodeDefsLoading
                }
                isError={
                  nodeDefsError
                }
              />

              <div
                ref={wrapperRef}
                className="relative min-w-0 flex-1"
                onDrop={onDrop}
                onDragOver={
                  onDragOver
                }
              >
                <ReactFlow
                  nodes={nodes}
                  edges={edges}
                  nodeTypes={
                    nodeTypes
                  }
                  onNodesChange={
                    onNodesChange
                  }
                  onEdgesChange={
                    onEdgesChange
                  }
                  onConnect={
                    onConnect
                  }
                  onSelectionChange={
                    onSelectionChange
                  }
                  onNodeDragStart={
                    onNodeDragStart
                  }
                  onBeforeDelete={
                    onBeforeDelete
                  }
                  deleteKeyCode={[
                    "Delete",
                    "Backspace",
                  ]}
                  multiSelectionKeyCode={[
                    "Meta",
                    "Control",
                    "Shift",
                  ]}
                  defaultEdgeOptions={{
                    type: "default",
                    animated: false,
                    style: {
                      stroke: isDark ? "rgba(255,255,255,0.35)" : "rgba(0,0,0,0.30)",
                      strokeWidth: 1.75,
                    },
                  }}
                  connectionLineType={
                    ConnectionLineType.Bezier
                  }
                  fitView
                  fitViewOptions={{
                    padding: 0.15,
                    maxZoom: 1.1,
                  }}
                  minZoom={0.1}
                  maxZoom={2}
                  proOptions={{
                    hideAttribution:
                      true,
                  }}
                  className="bg-background dark:bg-[#050505]"
                >
                  <Background
                    variant={
                      BackgroundVariant.Dots
                    }
                    gap={22}
                    size={1.4}
                    color={isDark ? "rgba(255,255,255,0.13)" : "rgba(0,0,0,0.15)"}
                  />

                  <Controls className="!shadow-md" />

                  <MiniMap
                    pannable
                    zoomable
                    className="!bg-card"
                    nodeClassName={() =>
                      "!fill-primary/40"
                    }
                  />

                  <Panel position="top-left">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={
                        autoLayout
                      }
                      disabled={
                        nodes.length ===
                        0
                      }
                      className="h-8 border-border bg-card/90 text-xs text-foreground/80 hover:bg-muted hover:text-foreground dark:border-white/[0.08] dark:bg-[#0a0a0a]/90 dark:text-white/70 dark:hover:bg-white/[0.06] dark:hover:text-white shadow-sm"
                    >
                      Auto layout
                    </Button>
                  </Panel>
                </ReactFlow>

                {nodes.length ===
                  0 && (
                  <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                    <p className="rounded-md border border-dashed border-border bg-card/80 px-4 py-3 text-sm text-muted-foreground dark:border-white/[0.08] dark:bg-[#0a0a0a]/80 dark:text-white/40">
                      Drag a node from the
                      left, or click one to
                      add it.
                    </p>
                  </div>
                )}

                <ValidationPanel
                  result={
                    showValidation
                      ? validation
                      : null
                  }
                  onClose={() =>
                    setShowValidation(
                      false,
                    )
                  }
                  onFocusNode={
                    focusNode
                  }
                />
              </div>

              {inspectorOpen && (
                <ConfigPanel
                  node={selectedNode}
                  def={
                    selectedNode?.type
                      ? defsByType[
                          selectedNode.type
                        ]
                      : undefined
                  }
                  upstreamIds={
                    upstreamIds
                  }
                  onClose={() =>
                    setInspectorOpen(
                      false,
                    )
                  }
                  onLabelChange={
                    onLabelChange
                  }
                  onConfigChange={
                    onConfigChange
                  }
                />
              )}
            </div>
          </div>

          <PublishDialog
            open={publishOpen}
            onOpenChange={
              setPublishOpen
            }
            pending={
              publishMutation.isPending
            }
            blockingValidation={
              publishBlock
            }
            onPublish={(note) =>
              publishMutation.mutate(
                note,
              )
            }
          />

          <GenerateWorkflowDialog
            open={generateOpen}
            onOpenChange={
              setGenerateOpen
            }
            onAccept={
              acceptGenerated
            }
          />

          <WebhookDialog
            open={webhookOpen}
            onOpenChange={
              setWebhookOpen
            }
            workflowId={
              workflow.id
            }
            canManage={
              canManageWebhook
            }
          />
        </NodeDefsContext.Provider>
      </ErrorNodesContext.Provider>
    );
  },
);