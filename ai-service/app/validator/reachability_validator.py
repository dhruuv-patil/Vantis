"""Reachability validator: ensures every node in the workflow is reachable from the trigger."""

from __future__ import annotations

from collections import deque
from typing import Any

from app.node_catalog import CATALOG_BY_TYPE
from app.validator.models import Severity, ValidationIssue


def validate_reachability(
    nodes: list[dict[str, Any]], edges: list[dict[str, Any]]
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    node_ids = [n["id"] for n in nodes if "id" in n and isinstance(n["id"], str)]
    nodes_by_id = {n["id"]: n for n in nodes if "id" in n and isinstance(n["id"], str)}

    triggers = [
        nid
        for nid in node_ids
        if nodes_by_id[nid].get("type") in CATALOG_BY_TYPE
        and CATALOG_BY_TYPE[nodes_by_id[nid]["type"]].get("trigger", False)
    ]

    if not triggers:
        # Trigger absence is already flagged by graph validator
        return issues

    # Adjacency map
    adjacency: dict[str, list[str]] = {nid: [] for nid in node_ids}
    for edge in edges:
        s = edge.get("source")
        t = edge.get("target")
        if s in adjacency and t in nodes_by_id:
            adjacency[s].append(t)

    # BFS from all triggers
    visited: set[str] = set()
    queue = deque(triggers)
    for t in triggers:
        visited.add(t)

    while queue:
        curr = queue.popleft()
        for nxt in adjacency.get(curr, []):
            if nxt not in visited:
                visited.add(nxt)
                queue.append(nxt)

    # Any node not visited is unreachable and MUST fail validation
    for nid in node_ids:
        if nid not in visited:
            node_label = nodes_by_id[nid].get("label") or nid
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="UNREACHABLE_NODE",
                    message=(
                        f"Node '{nid}' ({node_label}) has no incoming path from the trigger and can never execute. "
                        "All nodes in a Vantis workflow must be connected and reachable."
                    ),
                    node_id=nid,
                    path=f"nodes[{nid}]",
                )
            )

    return issues
