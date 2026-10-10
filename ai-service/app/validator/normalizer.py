"""Workflow normalizer: produces canonical, deterministic, builder-ready workflow JSON."""

from __future__ import annotations

from collections import deque
from typing import Any

from app.node_catalog import CATALOG_BY_TYPE


def normalize_workflow(data: dict[str, Any]) -> dict[str, Any]:
    """Deterministically normalizes a validated workflow into canonical shape.

    - Computes clean topological positions (x, y) so nodes never overlap.
    - Sets canonical edge IDs: `e_{source}_{target}_{sourceHandle}`.
    - Fills default labels from the catalog if omitted.
    - Orders nodes topologically and edges canonically.
    - Ensures idempotency: running twice produces identical JSON.
    """
    raw_nodes: list[dict[str, Any]] = data.get("nodes", []) or []
    raw_edges: list[dict[str, Any]] = data.get("edges", []) or []
    notes = data.get("notes")

    nodes_by_id = {n["id"]: dict(n) for n in raw_nodes if "id" in n and isinstance(n["id"], str)}

    # Build adjacency & calculate in-degrees for topological layout
    adjacency: dict[str, list[str]] = {nid: [] for nid in nodes_by_id}
    indegree: dict[str, int] = {nid: 0 for nid in nodes_by_id}

    for edge in raw_edges:
        s = edge.get("source")
        t = edge.get("target")
        if s in nodes_by_id and t in nodes_by_id and s != t:
            adjacency[s].append(t)
            indegree[t] += 1

    # Topological level (depth) calculation
    depth: dict[str, int] = {nid: 0 for nid in nodes_by_id}
    queue = deque([nid for nid, deg in indegree.items() if deg == 0])

    # If no 0-indegree (should not happen after validation), pick triggers or first node
    if not queue and nodes_by_id:
        queue = deque([next(iter(nodes_by_id))])

    visited_topo: list[str] = []
    while queue:
        curr = queue.popleft()
        visited_topo.append(curr)
        curr_depth = depth[curr]
        for neighbor in sorted(adjacency.get(curr, [])):
            depth[neighbor] = max(depth[neighbor], curr_depth + 1)
            indegree[neighbor] -= 1
            if indegree[neighbor] == 0:
                queue.append(neighbor)

    # Any remaining unvisited nodes get added at the max depth
    for nid in nodes_by_id:
        if nid not in visited_topo:
            visited_topo.append(nid)

    # Group nodes by topological depth to assign positions
    depth_buckets: dict[int, list[str]] = {}
    for nid in visited_topo:
        d = depth.get(nid, 0)
        depth_buckets.setdefault(d, []).append(nid)

    # Normalize nodes
    normalized_nodes: list[dict[str, Any]] = []
    for d, bucket in sorted(depth_buckets.items()):
        # Sort nodes within bucket deterministically by id
        for col_idx, nid in enumerate(sorted(bucket)):
            orig = nodes_by_id[nid]
            ntype = orig.get("type", "")
            defn = CATALOG_BY_TYPE.get(ntype, {})
            label = orig.get("label") or orig.get("data", {}).get("label") or defn.get("label", ntype)

            config = orig.get("config") or orig.get("data", {}).get("config") or {}
            # Keep config keys clean and sorted
            sorted_config = {k: config[k] for k in sorted(config.keys()) if config[k] is not None}

            # Canonical coordinates
            x_pos = 160 + (col_idx * 260)
            y_pos = 80 + (d * 140)

            node_dict: dict[str, Any] = {
                "id": nid,
                "type": ntype,
                "label": label,
                "position": {"x": x_pos, "y": y_pos},
                "data": {
                    "label": label,
                    "config": sorted_config,
                },
                "config": sorted_config,
            }
            normalized_nodes.append(node_dict)

    # Normalize edges
    normalized_edges: list[dict[str, Any]] = []
    seen_edge_keys: set[str] = set()

    for edge in raw_edges:
        s = edge.get("source")
        t = edge.get("target")
        if not s or not t or s not in nodes_by_id or t not in nodes_by_id:
            continue

        handle = edge.get("sourceHandle") or edge.get("source_handle")
        if handle:
            handle = str(handle).strip()

        key = f"{s}->{t}#{handle or ''}"
        if key in seen_edge_keys:
            continue
        seen_edge_keys.add(key)

        handle_suffix = f"-{handle}" if handle else ""
        canonical_edge_id = f"e-{s}-{t}{handle_suffix}"

        edge_dict: dict[str, Any] = {
            "id": canonical_edge_id,
            "source": s,
            "target": t,
        }
        if handle:
            edge_dict["sourceHandle"] = handle
            edge_dict["source_handle"] = handle

        normalized_edges.append(edge_dict)

    # Sort edges deterministically
    normalized_edges.sort(key=lambda e: (e["source"], e["target"], e.get("sourceHandle") or ""))

    result: dict[str, Any] = {
        "nodes": normalized_nodes,
        "edges": normalized_edges,
    }
    if notes and isinstance(notes, str):
        result["notes"] = notes.strip()

    return result
