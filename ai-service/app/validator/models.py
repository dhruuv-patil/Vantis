"""Validation models and issue reporting for Vantis workflow generation.

These models represent structured validation findings that can be:
1. Evaluated to block invalid drafts / publishing / execution.
2. Serialized to machine-readable feedback for the AI repair loop.
3. Formatted into safe user-facing and observability metrics.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


class Severity(str, Enum):
    ERROR = "ERROR"
    WARNING = "WARNING"


@dataclass
class ValidationIssue:
    severity: Severity
    code: str
    message: str
    node_id: str | None = None
    edge_id: str | None = None
    path: str | None = None
    validator: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "severity": self.severity.value,
            "code": self.code,
            "message": self.message,
            "nodeId": self.node_id,
            "edgeId": self.edge_id,
            "path": self.path,
            "validator": self.validator,
        }

    def to_repair_error(self) -> dict[str, Any]:
        """Compact machine-readable error for LLM repair loop without secrets or stacktraces."""
        path = self.path or (f"nodes[{self.node_id}]" if self.node_id else (f"edges[{self.edge_id}]" if self.edge_id else "workflow"))
        return {
            "path": path,
            "code": self.code,
            "message": self.message,
        }


@dataclass
class ValidationResult:
    valid: bool
    issues: list[ValidationIssue] = field(default_factory=list)

    @property
    def errors(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == Severity.ERROR]

    @property
    def warnings(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == Severity.WARNING]

    def to_dict(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "issues": [i.to_dict() for i in self.issues],
        }

    def to_repair_feedback(self) -> dict[str, Any]:
        return {
            "errors": [i.to_repair_error() for i in self.errors]
        }

    @classmethod
    def success(cls, warnings: list[ValidationIssue] | None = None) -> ValidationResult:
        return cls(valid=True, issues=warnings or [])

    @classmethod
    def failure(cls, errors: list[ValidationIssue], warnings: list[ValidationIssue] | None = None) -> ValidationResult:
        all_issues = list(errors) + (warnings or [])
        return cls(valid=False, issues=all_issues)
