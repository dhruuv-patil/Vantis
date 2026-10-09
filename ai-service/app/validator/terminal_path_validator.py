"""Terminal path validator: ensures every execution branch terminates in a meaningful outcome."""

from __future__ import annotations

from typing import Any

from app.node_catalog import CATALOG_BY_TYPE
from app.validator.models import Severity, ValidationIssue

# Branching nodes that require all their output paths to be explicitly wired
MANDATORY_BRANCH_NODES: dict[str, list[str]] = {
    "condition": ["true", "false"],
    "if_else": ["true", "false"],
    "human_approval": ["approved", "rejected"],
    "health_check": ["healthy", "unhealthy"],
    "circuit_breaker": ["open", "closed"],
    "rate_limiter": ["ok", "exceeded"],
    "sla_monitor": ["inSla", "breached"],
    "error_handler": ["handled", "risky"],
}


def validate_terminal_paths(
    nodes: list[dict[str, Any]], edges: list[dict[str, Any]]
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    nodes_by_id = {n["id"]: n for n in nodes if "id" in n and isinstance(n["id"], str)}

    # Map of (source_node_id, handle) -> list of targets
    outgoing_by_handle: dict[tuple[str, str], list[str]] = {}
    outgoing_total: dict[str, int] = {nid: 0 for nid in nodes_by_id}

    for edge in edges:
        s = edge.get("source")
        t = edge.get("target")
        h = edge.get("sourceHandle") or edge.get("source_handle") or "out"
        if s and t:
            outgoing_by_handle.setdefault((s, h), []).append(t)
            outgoing_total[s] = outgoing_total.get(s, 0) + 1

    # 1. Check that mandatory branching nodes do not drop branches
    for nid, node in nodes_by_id.items():
        ntype = node.get("type")
        if ntype in MANDATORY_BRANCH_NODES:
            required_branches = MANDATORY_BRANCH_NODES[ntype]
            for branch in required_branches:
                targets = outgoing_by_handle.get((nid, branch), [])
                if not targets:
                    code = "APPROVAL_BRANCH_MISSING" if ntype == "human_approval" else "MISSING_BRANCH_TARGET"
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code=code,
                            message=(
                                f"Branching node '{nid}' ({ntype}) is missing a connection for the '{branch}' path. "
                                f"Every branch ({', '.join(required_branches)}) must lead to a meaningful outcome."
                            ),
                            node_id=nid,
                            path=f"nodes[{nid}]",
                        )
                    )

    # 2. Check that the workflow has at least one terminal node (sink)
    # A single trigger without other nodes is acceptable if it is the only node.
    terminal_nodes = [nid for nid, count in outgoing_total.items() if count == 0]
    if len(nodes) > 1 and len(terminal_nodes) == 0:
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="NO_TERMINAL_PATH",
                message="The workflow has no terminal node. Every execution path must terminate at an outcome or action.",
                path="edges",
            )
        )

    return issues
