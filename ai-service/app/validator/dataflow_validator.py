"""Dataflow validator: validates variable dependencies, topological scope, and output field contracts."""

from __future__ import annotations

import re
from typing import Any

from app.node_catalog import CATALOG_BY_TYPE, get_known_output_fields
from app.validator.models import Severity, ValidationIssue

VARIABLE_PATTERN = re.compile(r"\{\{\s*([^}]+?)\s*\}\}")


def _extract_references(text: str) -> list[str]:
    """Find all {{ expression }} references within a string."""
    return VARIABLE_PATTERN.findall(text)


def _scan_obj_for_refs(obj: Any) -> list[str]:
    """Recursively scan strings, dicts, and lists for variable references."""
    refs: list[str] = []
    if isinstance(obj, str):
        refs.extend(_extract_references(obj))
    elif isinstance(obj, dict):
        for v in obj.values():
            refs.extend(_scan_obj_for_refs(v))
    elif isinstance(obj, list):
        for item in obj:
            refs.extend(_scan_obj_for_refs(item))
    return refs


def _compute_ancestors(nodes_by_id: dict[str, dict[str, Any]], edges: list[dict[str, Any]]) -> dict[str, set[str]]:
    """Compute the set of all upstream ancestor node IDs for every node."""
    # Predecessors map
    predecessors: dict[str, set[str]] = {nid: set() for nid in nodes_by_id}
    for edge in edges:
        s = edge.get("source")
        t = edge.get("target")
        if s in nodes_by_id and t in nodes_by_id:
            predecessors[t].add(s)

    # Transitive closure for DAG ancestors
    ancestors: dict[str, set[str]] = {nid: set() for nid in nodes_by_id}
    for nid in nodes_by_id:
        visited: set[str] = set()
        stack = list(predecessors[nid])
        while stack:
            curr = stack.pop()
            if curr not in visited:
                visited.add(curr)
                stack.extend(predecessors.get(curr, set()))
        ancestors[nid] = visited

    return ancestors


def validate_dataflow(
    nodes: list[dict[str, Any]], edges: list[dict[str, Any]]
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    nodes_by_id = {n["id"]: n for n in nodes if "id" in n and isinstance(n["id"], str)}
    ancestors = _compute_ancestors(nodes_by_id, edges)

    # Identify trigger node(s)
    trigger_nodes = {
        n["id"]
        for n in nodes
        if n.get("type") in CATALOG_BY_TYPE and CATALOG_BY_TYPE[n["type"]].get("trigger", False)
    }

    builtin_roots = {
        "trigger", "item", "loop", "env", "secrets", "secret",
        "params", "variables", "vars", "context", "global", "globals"
    }

    for node in nodes:
        node_id = node.get("id")
        if not node_id:
            continue

        config = node.get("config") or {}
        refs = _scan_obj_for_refs(config)
        node_ancestors = ancestors.get(node_id, set())

        for ref in refs:
            raw_expr = ref.strip()
            # Normalize complex reference formats like:
            # nodes.customer.output.email -> node=customer, field=email
            # customer.email -> node=customer, field=email
            # trigger.body.id -> root=trigger
            parts = [p.strip() for p in raw_expr.split(".") if p.strip()]
            if not parts:
                continue

            first_segment = parts[0]
            target_node_id: str | None = None
            field_name: str | None = None

            if first_segment == "nodes" and len(parts) >= 2:
                target_node_id = parts[1]
                if len(parts) >= 4 and parts[2] == "output":
                    field_name = parts[3]
                elif len(parts) >= 3:
                    field_name = parts[2]
            elif first_segment in builtin_roots:
                # Builtin reference like trigger.body or secrets.apiKey
                if first_segment == "trigger" and not trigger_nodes:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="UNDEFINED_REFERENCE",
                            message=f"Node '{node_id}' references 'trigger' but no trigger node exists.",
                            node_id=node_id,
                            path=f"nodes[{node_id}].config",
                        )
                    )
                continue
            elif first_segment in nodes_by_id:
                target_node_id = first_segment
                if len(parts) >= 2:
                    field_name = parts[1]
            elif len(parts) == 1:
                # Single variable without dot separator is a workflow parameter, template token, or env var
                continue
            else:
                # Segment with property access that is not a known node ID or builtin
                issues.append(
                    ValidationIssue(
                        severity=Severity.ERROR,
                        code="UNDEFINED_REFERENCE",
                        message=(
                            f"Node '{node_id}' references unknown entity or node '{first_segment}' in '{{{{{raw_expr}}}}}'. "
                            "Referenced node does not exist in the workflow."
                        ),
                        node_id=node_id,
                        path=f"nodes[{node_id}].config",
                    )
                )
                continue

            # Verify that target_node_id is an upstream ancestor!
            if target_node_id:
                if target_node_id not in nodes_by_id:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="UNDEFINED_REFERENCE",
                            message=(
                                f"Node '{node_id}' references non-existent node '{target_node_id}' "
                                f"in '{{{{{raw_expr}}}}}'."
                            ),
                            node_id=node_id,
                            path=f"nodes[{node_id}].config",
                        )
                    )
                elif target_node_id == node_id:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="SELF_REFERENCE",
                            message=f"Node '{node_id}' attempts to reference its own output before executing.",
                            node_id=node_id,
                            path=f"nodes[{node_id}].config",
                        )
                    )
                elif target_node_id not in node_ancestors:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="OUT_OF_SCOPE_REFERENCE",
                            message=(
                                f"Node '{node_id}' references node '{target_node_id}', but '{target_node_id}' is not "
                                f"an upstream ancestor in this execution path. It has not executed yet or is on a separate branch."
                            ),
                            node_id=node_id,
                            path=f"nodes[{node_id}].config",
                        )
                    )
                else:
                    # Validate output field if known
                    target_type = nodes_by_id[target_node_id].get("type", "")
                    known_outputs = get_known_output_fields(target_type)
                    if field_name and known_outputs:
                        # Allow nested paths inside body/data/output/payload/rows/params/headers
                        container_fields = {"body", "data", "output", "payload", "rows", "document", "results", "params", "headers", "result"}
                        if field_name not in known_outputs and not any(cf in known_outputs for cf in container_fields):
                            issues.append(
                                ValidationIssue(
                                    severity=Severity.ERROR,
                                    code="UNDEFINED_OUTPUT_FIELD",
                                    message=(
                                        f"Node '{node_id}' references output field '{field_name}' on node '{target_node_id}' "
                                        f"({target_type}), which does not produce that field. Known outputs: {', '.join(sorted(known_outputs))}."
                                    ),
                                    node_id=node_id,
                                    path=f"nodes[{node_id}].config",
                                )
                            )

    return issues
