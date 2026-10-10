"""Security validator: protects against secret leaks, SSRF, infinite retries, and dangerous parameters."""

from __future__ import annotations

import ipaddress
import re
from typing import Any
from urllib.parse import urlparse

from app.validator.models import Severity, ValidationIssue

# Secret patterns
SECRET_PATTERNS = [
    (re.compile(r"sk-[a-zA-Z0-9_\-]{20,}"), "OpenAI/API secret key"),
    (re.compile(r"ghp_[a-zA-Z0-9]{36}"), "GitHub Personal Access Token"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS Access Key ID"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "Cryptographic private key"),
    (re.compile(r"(?i)\bBearer\s+[a-zA-Z0-9_\-\.]{25,}\b"), "Bearer authorization token"),
    (re.compile(r"(?i)xox[baprs]-[0-9]{10,}-[0-9]{10,}-[a-zA-Z0-9]{24,}"), "Slack token"),
]

BLOCKED_HOSTNAMES = {
    "localhost",
    "127.0.0.1",
    "0.0.0.0",
    "host.docker.internal",
    "kubernetes.default.svc",
    "metadata.google.internal",
}

BLOCKED_SUFFIXES = (
    ".localhost",
    ".local",
    ".internal",
    ".cluster.local",
    ".svc",
)


def _is_blocked_ip(ip_str: str) -> bool:
    try:
        ip = ipaddress.ip_address(ip_str)
        return (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip_str == "169.254.169.254"  # AWS/GCP cloud metadata
        )
    except ValueError:
        return False


def _validate_url_ssrf(url_str: str) -> str | None:
    """Validate URL destination against SSRF rules."""
    if not url_str or not isinstance(url_str, str):
        return None

    # Skip validation if URL is purely a variable template e.g. {{trigger.url}}
    cleaned = url_str.strip()
    if cleaned.startswith("{{") and cleaned.endswith("}}"):
        return None

    try:
        parsed = urlparse(cleaned)
    except Exception:
        return "Invalid URL format."

    scheme = (parsed.scheme or "").lower()
    if scheme not in ("http", "https"):
        return f"Prohibited URL scheme '{scheme}'. Only http and https endpoints are allowed."

    host = (parsed.hostname or "").lower()
    if not host:
        # If host contains variable interpolation like {{api_url}}, allow
        if "{{" in cleaned:
            return None
        return "URL is missing a valid hostname."

    if host in BLOCKED_HOSTNAMES or any(host.endswith(suffix) for suffix in BLOCKED_SUFFIXES):
        return f"Target host '{host}' resolves to internal/private infrastructure and is blocked for security."

    if _is_blocked_ip(host):
        return f"Target IP address '{host}' is in a private, loopback, or cloud-metadata range and is blocked."

    return None


def _scan_for_secrets(value: Any) -> str | None:
    """Scan strings, dicts, or lists recursively for secret patterns."""
    if isinstance(value, str):
        for pattern, desc in SECRET_PATTERNS:
            if pattern.search(value):
                return desc
    elif isinstance(value, dict):
        # Look for explicit password / apiKey fields with actual values
        for k, v in value.items():
            k_lower = str(k).lower()
            if k_lower in ("apikey", "api_key", "secret", "password") and isinstance(v, str) and v.strip() and not ("{{" in v):
                # Only flag if not an empty placeholder or standard env ref
                if len(v.strip()) > 3 and not v.strip().startswith("conn-") and not v.strip().startswith("cred-"):
                    return f"Hardcoded credential in '{k}'"
            found = _scan_for_secrets(v)
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = _scan_for_secrets(item)
            if found:
                return found
    return None


def validate_security(nodes: list[dict[str, Any]]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    for node in nodes:
        node_id = str(node.get("id", ""))
        node_type = str(node.get("type", ""))
        config = node.get("config") or {}
        node_path = f"nodes[{node_id}]"

        # 1. Scan config for hardcoded secrets
        secret_found = _scan_for_secrets(config)
        if secret_found:
            issues.append(
                ValidationIssue(
                    severity=Severity.ERROR,
                    code="CREDENTIAL_LEAK_DETECTED",
                    message=(
                        f"Node '{node_id}' contains obvious hardcoded secret material ({secret_found}). "
                        "AI-generated workflows must reference credentials via 'integrationId' or environment variables, "
                        "never plaintext keys or passwords."
                    ),
                    node_id=node_id,
                    path=f"{node_path}.config",
                )
            )

        # 2. SSRF URL validation
        url_fields = ["url", "siteUrl", "endpoint", "uri"]
        for uf in url_fields:
            if uf in config:
                u_val = config.get(uf)
                if isinstance(u_val, str) and u_val.strip():
                    ssrf_err = _validate_url_ssrf(u_val)
                    if ssrf_err:
                        issues.append(
                            ValidationIssue(
                                severity=Severity.ERROR,
                                code="SSRF_TARGET_BLOCKED",
                                message=f"Node '{node_id}' ({node_type}) HTTP target URL is unsafe: {ssrf_err}",
                                node_id=node_id,
                                path=f"{node_path}.config.{uf}",
                            )
                        )

        # 3. Retry bounds
        if "maxAttempts" in config:
            raw_attempts = config.get("maxAttempts")
            try:
                attempts = int(raw_attempts)
                if attempts <= 0 or attempts > 10:
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="INVALID_RETRY_POLICY",
                            message=(
                                f"Node '{node_id}' specifies maxAttempts={attempts}. "
                                "Retry attempts must be between 1 and 10 to prevent infinite loops."
                            ),
                            node_id=node_id,
                            path=f"{node_path}.config.maxAttempts",
                        )
                    )
            except (ValueError, TypeError):
                issues.append(
                    ValidationIssue(
                        severity=Severity.ERROR,
                        code="INVALID_RETRY_POLICY",
                        message=f"Node '{node_id}' specifies an invalid maxAttempts value.",
                        node_id=node_id,
                        path=f"{node_path}.config.maxAttempts",
                    )
                )

        # 4. Timeout bounds
        for tf in ("timeoutSeconds", "timeoutMs"):
            if tf in config:
                raw_timeout = config.get(tf)
                try:
                    t_val = float(raw_timeout)
                    limit = 3600000 if tf == "timeoutMs" else 3600
                    if t_val <= 0 or t_val > limit:
                        issues.append(
                            ValidationIssue(
                                severity=Severity.ERROR,
                                code="INVALID_TIMEOUT",
                                message=(
                                    f"Node '{node_id}' specifies an invalid or unbounded timeout ({t_val}). "
                                    f"Must be positive and <= {limit}."
                                ),
                                node_id=node_id,
                                path=f"{node_path}.config.{tf}",
                            )
                        )
                except (ValueError, TypeError):
                    issues.append(
                        ValidationIssue(
                            severity=Severity.ERROR,
                            code="INVALID_TIMEOUT",
                            message=f"Node '{node_id}' specifies an invalid timeout value.",
                            node_id=node_id,
                            path=f"{node_path}.config.{tf}",
                        )
                    )

    return issues
