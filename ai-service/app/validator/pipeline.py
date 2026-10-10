"""Comprehensive workflow validation pipeline."""

from __future__ import annotations

from typing import Any

from app.validator.dataflow_validator import validate_dataflow
from app.validator.expression_validator import validate_expressions
from app.validator.graph_validator import validate_graph
from app.validator.models import Severity, ValidationIssue, ValidationResult
from app.validator.reachability_validator import validate_reachability
from app.validator.registry_validator import validate_registry
from app.validator.schema_validator import validate_schema
from app.validator.security_validator import validate_security
from app.validator.terminal_path_validator import validate_terminal_paths


def validate_workflow_graph(data: Any) -> ValidationResult:
    """Run all schema, registry, graph, reachability, terminal path, dataflow, and security validators.

    Pure and side-effect free. Fast and in-memory.
    """
    all_issues: list[ValidationIssue] = []

    def _tag(issues: list[ValidationIssue], name: str) -> list[ValidationIssue]:
        for i in issues:
            if not i.validator:
                i.validator = name
        return issues

    # 1. Schema validation
    schema_issues = _tag(validate_schema(data), "schema_validator")
    all_issues.extend(schema_issues)
    if any(i.severity == Severity.ERROR for i in schema_issues):
        # Cannot run deeper graph checks if basic JSON shape is corrupted
        return ValidationResult.failure(
            [i for i in all_issues if i.severity == Severity.ERROR],
            [i for i in all_issues if i.severity == Severity.WARNING],
        )

    nodes: list[dict[str, Any]] = data.get("nodes", [])
    edges: list[dict[str, Any]] = data.get("edges", [])

    # 2. Registry validation
    all_issues.extend(_tag(validate_registry(nodes), "registry_validator"))

    # 3. Graph validation (triggers, edge connectivity, ports, cycles)
    all_issues.extend(_tag(validate_graph(nodes, edges), "graph_validator"))

    # 4. Reachability analysis (all nodes must connect from trigger)
    all_issues.extend(_tag(validate_reachability(nodes, edges), "reachability_validator"))

    # 5. Terminal path validation (branches must terminate at valid outcomes)
    all_issues.extend(_tag(validate_terminal_paths(nodes, edges), "terminal_path_validator"))

    # 6. Dataflow validation (variable resolution, topological ancestor scope)
    all_issues.extend(_tag(validate_dataflow(nodes, edges), "dataflow_validator"))

    # 7. Expression validation (safe operators, no script injection)
    all_issues.extend(_tag(validate_expressions(nodes), "expression_validator"))

    # 8. Security policy validation (secrets, SSRF, bounded retries, timeouts)
    all_issues.extend(_tag(validate_security(nodes), "security_validator"))

    errors = [i for i in all_issues if i.severity == Severity.ERROR]
    warnings = [i for i in all_issues if i.severity == Severity.WARNING]

    if errors:
        return ValidationResult.failure(errors, warnings)

    return ValidationResult.success(warnings)
