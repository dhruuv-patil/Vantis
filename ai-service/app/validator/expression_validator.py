"""Expression validator: audits conditions and logic expressions against arbitrary code injection."""

from __future__ import annotations

import re
from typing import Any

from app.validator.models import Severity, ValidationIssue

# Dangerous patterns attempting arbitrary code evaluation
DANGEROUS_EXPRESSION_PATTERNS = [
    re.compile(r"\b(eval|exec|__import__|compile)\s*\(", re.IGNORECASE),
    re.compile(r"\b(os\.|sys\.|subprocess\.|shutil\.)", re.IGNORECASE),
    re.compile(r"\b(require|process\.env|child_process)\b", re.IGNORECASE),
    re.compile(r"\b(function\s*\(|new\s+Function)\b", re.IGNORECASE),
    re.compile(r"(__proto__|constructor|prototype)\b", re.IGNORECASE),
    re.compile(r"\b(curl|wget|bash|sh|powershell|cmd)\s+", re.IGNORECASE),
    re.compile(r"[;\|&`]\s*(rm|cat|bash|sh|curl|wget)\b", re.IGNORECASE),
    re.compile(r"\b(DROP\s+TABLE|DELETE\s+FROM|UNION\s+SELECT)\b", re.IGNORECASE),
]

ALLOWED_OPERATORS = ["==", "!=", ">=", "<=", ">", "<", "contains"]


def validate_expression_safety(expr: str) -> str | None:
    """Return an error reason if the expression looks like malicious code injection."""
    for pattern in DANGEROUS_EXPRESSION_PATTERNS:
        if pattern.search(expr):
            return f"Dangerous or unauthorized code execution syntax detected: '{pattern.pattern}'."
    return None


def validate_expressions(nodes: list[dict[str, Any]]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    for node in nodes:
        node_id = str(node.get("id", ""))
        node_type = str(node.get("type", ""))
        config = node.get("config") or {}

        # Expression fields exist on condition, if_else, conditional_wait, while
        fields_to_check = []
        if node_type in ("condition", "conditional_wait", "while"):
            if "expression" in config:
                fields_to_check.append(("expression", config.get("expression")))
            if "condition" in config:
                fields_to_check.append(("condition", config.get("condition")))
        elif node_type == "if_else":
            if "expression" in config:
                fields_to_check.append(("expression", config.get("expression")))
            if "conditions" in config:
                conds = config.get("conditions")
                if isinstance(conds, list):
                    for i, c in enumerate(conds):
                        fields_to_check.append((f"conditions[{i}]", str(c)))
                elif isinstance(conds, str):
                    fields_to_check.append(("conditions", conds))

        for field_name, expr_val in fields_to_check:
            if not isinstance(expr_val, str) or not expr_val.strip():
                continue

            clean_expr = expr_val.strip()
            # 1. Check for dangerous script execution attempts
            danger_err = validate_expression_safety(clean_expr)
            if danger_err:
                issues.append(
                    ValidationIssue(
                        severity=Severity.ERROR,
                        code="UNSAFE_EXPRESSION",
                        message=f"Node '{node_id}' contains unsafe expression: {danger_err}",
                        node_id=node_id,
                        path=f"nodes[{node_id}].config.{field_name}",
                    )
                )
                continue

            # 2. Check for syntax: remove {{ ... }} blocks then verify operators
            sanitized = re.sub(r"\{\{.*?\}\}", "IDENT", clean_expr).strip()
            # If there is an operator, make sure left and right sides exist
            has_op = any(op in sanitized for op in ALLOWED_OPERATORS)
            if has_op:
                found_op = next(op for op in ALLOWED_OPERATORS if op in sanitized)
                parts = sanitized.split(found_op, 1)
                left = parts[0].strip()
                right = parts[1].strip() if len(parts) > 1 else ""
                if not left or not right:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="MALFORMED_EXPRESSION",
                            message=(
                                f"Node '{node_id}' expression '{clean_expr}' is incomplete around operator '{found_op}'. "
                                "Both left and right operands are required."
                            ),
                            node_id=node_id,
                            path=f"nodes[{node_id}].config.{field_name}",
                        )
                    )

    return issues
