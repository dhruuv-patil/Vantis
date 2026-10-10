"""Graph validator: validates connectivity, ports, cycles, and triggers."""

from __future__ import annotations

from collections import deque
from typing import Any

from app.node_catalog import CATALOG_BY_TYPE, get_allowed_output_handles
from app.validator.models import Severity, ValidationIssue


def validate_graph(
    nodes: list[dict[str, Any]], edges: list[dict[str, Any]]
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    nodes_by_id: dict[str, dict[str, Any]] = {
        n["id"]: n for n in nodes if "id" in n and isinstance(n["id"], str)
    }

    # 1. Trigger node count
    triggers = [
        n
        for n in nodes
        if n.get("type") in CATALOG_BY_TYPE and CATALOG_BY_TYPE[n["type"]].get("trigger", False)
    ]

    if len(triggers) == 0:
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="NO_TRIGGER",
                message="A workflow needs exactly one trigger node to start it.",
                path="nodes",
            )
        )
    elif len(triggers) > 1:
        trigger_ids = ", ".join(f"'{t['id']}'" for t in triggers)
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="MULTIPLE_TRIGGERS",
                message=f"A workflow can only have one trigger node, but found {len(triggers)}: {trigger_ids}.",
                path="nodes",
            )
        )

    # 2. Validate edges
    for idx, edge in enumerate(edges):
        source = edge.get("source")
        target = edge.get("target")
        edge_id = str(edge.get("id", f"edge_{idx}"))
        edge_path = f"edges[{edge_id}]"

        if not source or not target:
            continue

        if source == target:
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="SELF_LOOP_DETECTED",
                    message=f"Node '{source}' has a connection pointing directly to itself, creating an invalid loop.",
                    edge_id=edge_id,
                    node_id=source,
                    path=edge_path,
                )
            )

        if source not in nodes_by_id:
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="EDGE_DANGLING",
                    message=f"Connection references nonexistent source node '{source}'.",
                    edge_id=edge_id,
                    path=f"{edge_path}.source",
                )
            )

        if target not in nodes_by_id:
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="EDGE_DANGLING",
                    message=f"Connection references nonexistent target node '{target}'.",
                    edge_id=edge_id,
                    path=f"{edge_path}.target",
                )
            )

        # Trigger cannot have an incoming connection
        target_node = nodes_by_id.get(target)
        if target_node:
            t_type = target_node.get("type")
            if t_type in CATALOG_BY_TYPE and CATALOG_BY_TYPE[t_type].get("trigger", False):
                issues.append(
                    ValidationIssue(
                        severity=Severity.ERROR,
                        code="TRIGGER_HAS_INPUT",
                        message=f"Trigger node '{target}' cannot have an incoming connection.",
                        edge_id=edge_id,
                        node_id=target,
                        path=edge_path,
                    )
                )

        # Validate source handle
        source_node = nodes_by_id.get(source)
        if source_node:
            s_type = source_node.get("type")
            if s_type in CATALOG_BY_TYPE:
                allowed_handles = get_allowed_output_handles(s_type)
                handle = edge.get("sourceHandle") or edge.get("source_handle")

                # If branching node (multiple outputs), sourceHandle is mandatory
                if len(allowed_handles) > 1:
                    if not handle:
                        issues.append(
                            ValidationIssue(
                                severity=Severity.ERROR,
                                code="MISSING_SOURCE_HANDLE",
                                message=(
                                    f"Connection from branching node '{source}' ({s_type}) must specify a sourceHandle. "
                                    f"Available outputs: {', '.join(allowed_handles)}."
                                ),
                                edge_id=edge_id,
                                node_id=source,
                                path=f"{edge_path}.sourceHandle",
                            )
                        )
                    elif handle not in allowed_handles:
                        issues.append(
                            ValidationIssue(
                                severity=Severity.ERROR,
                                code="INVALID_SOURCE_HANDLE",
                                message=(
                                    f"Invalid sourceHandle '{handle}' from node '{source}' ({s_type}). "
                                    f"Allowed outputs are: {', '.join(allowed_handles)}."
                                ),
                                edge_id=edge_id,
                                node_id=source,
                                path=f"{edge_path}.sourceHandle",
                            )
                        )
                elif handle and handle not in allowed_handles and handle != "out":
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="INVALID_SOURCE_HANDLE",
                            message=f"Node '{source}' ({s_type}) does not support output handle '{handle}'.",
                            edge_id=edge_id,
                            node_id=source,
                            path=f"{edge_path}.sourceHandle",
                        )
                    )

    # 3. Detect cycles using Kahn's algorithm
    valid_node_ids = set(nodes_by_id.keys())
    indegree: dict[str, int] = {nid: 0 for nid in valid_node_ids}
    adjacency: dict[str, list[str]] = {nid: [] for nid in valid_node_ids}

    for edge in edges:
        s = edge.get("source")
        t = edge.get("target")
        if s in valid_node_ids and t in valid_node_ids and s != t:
            adjacency[s].append(t)
            indegree[t] += 1

    queue = deque([nid for nid, deg in indegree.items() if deg == 0])
    visited_count = 0

    while queue:
        curr = queue.popleft()
        visited_count += 1
        for neighbor in adjacency[curr]:
            indegree[neighbor] -= 1
            if indegree[neighbor] == 0:
                queue.append(neighbor)

    if visited_count < len(valid_node_ids):
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="CYCLE_DETECTED",
                message="The workflow contains an illegal cycle or loop. Vantis workflows must be directed acyclic graphs.",
                path="edges",
            )
        )

    return issues
