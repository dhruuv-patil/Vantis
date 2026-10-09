"""Schema validator: validates root shape, node structure, and edge structure."""

from __future__ import annotations

import re
from typing import Any

from app.validator.models import Severity, ValidationIssue

VALID_ID_PATTERN = re.compile(r"^[a-zA-Z0-9_\-]+$")
ALLOWED_ROOT_KEYS = {"nodes", "edges", "notes"}


def validate_schema(data: Any) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    if not isinstance(data, dict):
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="MALFORMED_ROOT_OBJECT",
                message="Workflow payload must be a JSON object containing 'nodes' and 'edges'.",
                path="root",
            )
        )
        return issues

    # Reject unexpected root keys that might indicate malformed/hallucinated payloads
    for key in data.keys():
        if key not in ALLOWED_ROOT_KEYS:
            issues.append(
                ValidationIssue(
                    severity=Severity.WARNING,
                    code="UNKNOWN_ROOT_FIELD",
                    message=f"Unknown root-level field '{key}'. Expected only 'nodes', 'edges', and optional 'notes'.",
                    path=key,
                )
            )

    # Check nodes
    raw_nodes = data.get("nodes")
    if raw_nodes is None:
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="MISSING_NODES_LIST",
                message="Workflow must contain a 'nodes' array.",
                path="nodes",
            )
        )
        return issues

    if not isinstance(raw_nodes, list):
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="INVALID_NODES_TYPE",
                message="'nodes' must be a JSON array.",
                path="nodes",
            )
        )
        return issues

    if len(raw_nodes) == 0:
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="EMPTY_GRAPH",
                message="Add at least one trigger node to build a workflow.",
                path="nodes",
            )
        )
        return issues

    # Check edges
    raw_edges = data.get("edges")
    if raw_edges is not None and not isinstance(raw_edges, list):
        issues.append(
            ValidationIssue(
                severity=Severity.ERROR,
                code="INVALID_EDGES_TYPE",
                message="'edges' must be a JSON array.",
                path="edges",
            )
        )
        return issues

    # Validate individual nodes
    seen_node_ids: set[str] = set()
    for idx, node in enumerate(raw_nodes):
        node_path = f"nodes[{idx}]"
        if not isinstance(node, dict):
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="MALFORMED_NODE",
                    message=f"Node at index {idx} must be a JSON object.",
                    path=node_path,
                )
            )
            continue

        node_id = node.get("id")
        if not isinstance(node_id, str) or not node_id.strip():
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="NODE_MISSING_ID",
                    message=f"Node at index {idx} is missing a required string 'id'.",
                    path=f"{node_path}.id",
                )
            )
            continue

        clean_id = node_id.strip()
        if not VALID_ID_PATTERN.match(clean_id):
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="INVALID_NODE_ID_FORMAT",
                    message=f"Node id '{clean_id}' contains invalid characters. Use alphanumeric, dash, or underscore.",
                    node_id=clean_id,
                    path=f"{node_path}.id",
                )
            )

        if clean_id in seen_node_ids:
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="DUPLICATE_NODE_ID",
                    message=f"Two nodes share the id '{clean_id}'. Node ids must be unique.",
                    node_id=clean_id,
                    path=f"{node_path}.id",
                )
            )
        else:
            seen_node_ids.add(clean_id)

        node_type = node.get("type")
        if not isinstance(node_type, str) or not node_type.strip():
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="NODE_MISSING_TYPE",
                    message=f"Node '{clean_id}' is missing a required string 'type'.",
                    node_id=clean_id,
                    path=f"{node_path}.type",
                )
            )

        config = node.get("config")
        if config is not None and not isinstance(config, dict):
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="INVALID_CONFIG_TYPE",
                    message=f"Node '{clean_id}' configuration must be an object.",
                    node_id=clean_id,
                    path=f"{node_path}.config",
                )
            )

    # Validate individual edges
    for idx, edge in enumerate(raw_edges or []):
        edge_path = f"edges[{idx}]"
        if not isinstance(edge, dict):
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="MALFORMED_EDGE",
                    message=f"Edge at index {idx} must be a JSON object.",
                    path=edge_path,
                )
            )
            continue

        source = edge.get("source")
        target = edge.get("target")

        if not isinstance(source, str) or not source.strip():
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="EDGE_INCOMPLETE",
                    message=f"Edge at index {idx} is missing a required 'source' node id.",
                    path=f"{edge_path}.source",
                )
            )

        if not isinstance(target, str) or not target.strip():
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="EDGE_INCOMPLETE",
                    message=f"Edge at index {idx} is missing a required 'target' node id.",
                    path=f"{edge_path}.target",
                )
            )

    return issues
