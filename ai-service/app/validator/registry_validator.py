"""Node registry validator: enforces that every node and configuration strictly matches Vantis catalogue."""

from __future__ import annotations

import json
from typing import Any

from app.node_catalog import CATALOG_BY_TYPE, is_known_node_type
from app.validator.models import Severity, ValidationIssue


def validate_registry(nodes: list[dict[str, Any]]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    for idx, node in enumerate(nodes):
        node_id = str(node.get("id", f"node_{idx}"))
        node_type = node.get("type")
        config = node.get("config") or {}
        node_path = f"nodes[{node_id}]"

        if not isinstance(node_type, str) or not is_known_node_type(node_type):
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="UNKNOWN_NODE_TYPE",
                    message=f"Unknown node type '{node_type}'. Vantis only supports registered node types.",
                    node_id=node_id,
                    path=f"{node_path}.type",
                )
            )
            continue

        defn = CATALOG_BY_TYPE[node_type]
        registered_fields = defn.get("fields", {})

        # 1. Check for unknown/invented configuration properties
        for config_key in config.keys():
            if config_key not in registered_fields:
                issues.append(
                    ValidationIssue(
                        severity=Severity.ERROR,
                        code="UNSUPPORTED_CONFIG_KEY",
                        message=(
                            f"Node '{node_id}' ({node_type}) declares unsupported configuration key '{config_key}'. "
                            f"Allowed keys are: {', '.join(registered_fields.keys()) or '(none)'}."
                        ),
                        node_id=node_id,
                        path=f"{node_path}.config.{config_key}",
                    )
                )

        # 2. Check required fields and field types
        for field_key, field_spec in registered_fields.items():
            field_path = f"{node_path}.config.{field_key}"
            is_required = field_spec.get("required", False)
            field_val = config.get(field_key)

            if is_required:
                if field_val is None or (isinstance(field_val, str) and not field_val.strip()):
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="MISSING_REQUIRED_CONFIG",
                            message=(
                                f"Node '{node_id}' ({node_type}) is missing required configuration field '{field_key}' "
                                f"({field_spec.get('label', field_key)})."
                            ),
                            node_id=node_id,
                            path=field_path,
                        )
                    )
                    continue

            # If field is present, validate its type/enum
            if field_val is not None:
                field_type = field_spec.get("type", "string")
                options = field_spec.get("options", [])

                if field_type == "select" and options:
                    if isinstance(field_val, str) and field_val not in options:
                        # Also check case-insensitive match for common LLM deviations
                        valid_match = next((o for o in options if o.lower() == field_val.lower()), None)
                        if not valid_match:
                            issues.append(
                                ValidationIssue(
                                    severity=Severity.ERROR,
                                    code="INVALID_CONFIG_ENUM",
                                    message=(
                                        f"Field '{field_key}' on node '{node_id}' has invalid value '{field_val}'. "
                                        f"Allowed values are: {', '.join(options)}."
                                    ),
                                    node_id=node_id,
                                    path=field_path,
                                )
                            )

                elif field_type == "number":
                    if not isinstance(field_val, (int, float)):
                        try:
                            float(str(field_val).strip())
                        except ValueError:
                            issues.append(
                                ValidationIssue(
                                    severity=Severity.ERROR,
                                    code="INVALID_NUMBER_FIELD",
                                    message=f"Field '{field_key}' on node '{node_id}' must be a number.",
                                    node_id=node_id,
                                    path=field_path,
                                )
                            )

                elif field_type == "json":
                    if isinstance(field_val, str) and field_val.strip():
                        # Strings with interpolation like {{ ... }} are acceptable; otherwise check valid JSON
                        if not ("{{" in field_val and "}}" in field_val):
                            try:
                                json.loads(field_val)
                            except Exception:
                                issues.append(
                                    ValidationIssue(
                                        severity=Severity.ERROR,
                                        code="INVALID_JSON_FIELD",
                                        message=f"Field '{field_key}' on node '{node_id}' must be valid JSON.",
                                        node_id=node_id,
                                        path=field_path,
                                    )
                                )

    return issues
