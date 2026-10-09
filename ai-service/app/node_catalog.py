"""The node catalogue used to steer workflow generation and validation.

Mirrors the backend ``NodeRegistry`` (the single source of truth). The backend
re-validates every generated graph against its own registry, so this catalogue
ensures both the LLM generator prompt and the local Python validators use the exact
same node types and schemas.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

_DATA_PATH = Path(__file__).parent / "node_catalog_data.json"


def _load_data() -> list[dict[str, Any]]:
    if not _DATA_PATH.exists():
        return []
    with open(_DATA_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


_RAW_CATALOG: list[dict[str, Any]] = _load_data()

# Node definitions indexed by node type
CATALOG_BY_TYPE: dict[str, dict[str, Any]] = {
    entry["type"]: entry for entry in _RAW_CATALOG
}

# The classic NODE_CATALOG list structure matching previous format for backwards compatibility
NODE_CATALOG: list[dict[str, Any]] = []
for entry in _RAW_CATALOG:
    config_dict = {}
    for f_key, f_val in entry.get("fields", {}).items():
        desc = f_val.get("desc", f_val.get("label", ""))
        opts = f_val.get("options", [])
        if opts:
            desc = f"{desc} [one of: {', '.join(opts)}]"
        config_dict[f_key] = desc
    NODE_CATALOG.append({
        "type": entry["type"],
        "purpose": entry["purpose"],
        "outputs": entry["outputs"],
        "config": config_dict,
        "trigger": entry["trigger"],
        "category": entry.get("category", "ACTION"),
    })


def is_known_node_type(node_type: str) -> bool:
    """Check if the given node type exists in Vantis."""
    return node_type in CATALOG_BY_TYPE


def get_node_definition(node_type: str) -> dict[str, Any] | None:
    """Return the definition dict for a given node type, or None if unknown."""
    return CATALOG_BY_TYPE.get(node_type)


def get_allowed_output_handles(node_type: str) -> list[str]:
    """Return the allowed output handles (ports) for a node type."""
    defn = CATALOG_BY_TYPE.get(node_type)
    if not defn:
        return ["out"]
    return defn.get("outputs", ["out"])


# Standard output fields produced by node families for dataflow validation
STANDARD_NODE_OUTPUT_FIELDS: dict[str, set[str]] = {
    # Triggers
    "manual_trigger": {"out", "trigger", "timestamp", "user", "runId"},
    "webhook_trigger": {"out", "trigger", "body", "headers", "method", "query", "path"},
    "schedule_trigger": {"out", "trigger", "scheduledTime", "timestamp"},
    "interval_trigger": {"out", "trigger", "intervalSeconds", "timestamp"},
    "workflow_trigger": {"out", "trigger", "sourceWorkflowId", "output", "data"},
    "event_trigger": {"out", "trigger", "eventName", "payload", "timestamp"},
    "email_trigger": {"out", "trigger", "from", "subject", "body", "attachments"},
    "file_trigger": {"out", "trigger", "path", "filename", "size"},
    "error_trigger": {"out", "trigger", "errorCode", "message", "timestamp"},
    "http_poll_trigger": {"out", "trigger", "status", "body", "headers"},
    # HTTP
    "http_request": {"out", "status", "body", "headers", "durationMs"},
    "graphql_request": {"out", "data", "errors", "status"},
    "api_health_check": {"out", "healthy", "status", "latencyMs"},
    "api_poll": {"out", "status", "body", "data", "attempts"},
    "webhook_response": {"out", "status", "body"},
    "download_file": {"out", "filePath", "content", "bytes"},
    "upload_file": {"out", "status", "response"},
    "http_batch": {"out", "results", "count"},
    "http_retry": {"out", "status", "body", "attempts"},
    "wait_for_http": {"out", "status", "durationMs"},
    "rate_limited_request": {"out", "status", "body"},
    "paginated_api_request": {"out", "items", "pageCount"},
    # AI
    "ai_agent": {"out", "output", "text", "model", "tool_calls"},
    "llm_chat": {"out", "output", "text", "model"},
    "ai_router": {"out", "route", "confidence"},
    "ai_decision": {"out", "decision", "confidence", "rationale"},
    "text_classifier": {"out", "category", "confidence"},
    "text_summarizer": {"out", "summary"},
    "sentiment_analyzer": {"out", "sentiment", "score"},
    "entity_extractor": {"out", "entities"},
    "information_extractor": {"out", "fields"},
    "json_generator": {"out", "json", "output"},
    "ai_data_validator": {"out", "valid", "findings"},
    "ai_content_generator": {"out", "content"},
    "ai_researcher": {"out", "answer", "sources"},
    "ai_root_cause_analyzer": {"out", "causes", "confidence"},
    "ai_anomaly_investigator": {"out", "summary", "likelyCauses", "evidence", "recommendedActions"},
    # Logic
    "condition": {"true", "false", "result", "expression"},
    "if_else": {"true", "false", "result", "expression"},
    "switch": {"case1", "case2", "case3", "case4", "default", "matchedCase"},
    "router": {"route1", "route2", "route3", "route4", "route5", "route6", "default", "matchedRoute"},
    "filter": {"out", "items", "count"},
    "merge": {"out", "result", "merged"},
    "split": {"out", "parts", "chunks"},
    "for_each": {"out", "results", "count"},
    "loop": {"out", "results", "iterations"},
    "while": {"out", "results", "iterations"},
    "wait": {"out", "elapsedSeconds"},
    "delay": {"out", "seconds"},
    "conditional_wait": {"out", "result", "elapsedSeconds"},
    "human_approval": {"approved", "rejected", "decision", "notes", "by"},
    "try_catch": {"out", "output", "error", "success"},
    "stop_fail": {"out", "stopped", "message"},
    "retry": {"out", "attempts"},
    "exponential_backoff": {"out", "delaySeconds", "attempt"},
    "timeout": {"out", "durationMs", "exceeded"},
    "circuit_breaker": {"open", "closed", "state", "tripped"},
    "rate_limiter": {"ok", "exceeded", "allowed", "rate"},
    "deduplicate": {"passed", "duplicate", "key"},
    "idempotency_check": {"out", "processed", "key"},
    "error_handler": {"handled", "risky", "error", "sanitized"},
    "dead_letter_queue": {"out", "entry", "dlqId"},
    # Data transforms
    "set_fields": {"out", "values"},
    "map_fields": {"out", "mapping"},
    "rename_fields": {"out", "renames"},
    "remove_fields": {"out", "fields"},
    "json_parse": {"out", "parsed", "data"},
    "json_stringify": {"out", "text"},
    "csv_parse": {"out", "rows"},
    "csv_generate": {"out", "csv"},
    "xml_parse": {"out", "parsed"},
    "regex_extract": {"out", "match", "group"},
    "text_formatter": {"out", "text"},
    "date_formatter": {"out", "date", "formatted"},
    "transform": {"out", "mapping"},
    "template": {"out", "text"},
    # Database
    "postgresql": {"out", "rows", "rowCount"},
    "mysql": {"out", "rows", "rowCount"},
    "mongodb": {"out", "result", "document", "rows"},
    "redis_get": {"out", "value", "key"},
    "redis_set": {"out", "success", "key"},
    "database_query": {"out", "rows", "rowCount"},
    "database_insert": {"out", "affected"},
    "database_update": {"out", "affected"},
    "database_delete": {"out", "affected"},
    "database_transaction": {"out", "results", "committed"},
    # Integrations
    "telegram": {"out", "success", "messageId"},
    "twilio": {"out", "success", "sid"},
    "stripe": {"out", "customer", "data", "id"},
    "openai": {"out", "output", "text", "model"},
    "hubspot": {"out", "id", "contact", "deal"},
    "slack": {"out", "success", "ts"},
    "notification": {"out", "sent", "channel"},
    "discord": {"out", "success"},
    "teams": {"out", "success"},
    "email": {"out", "sent"},
    "gmail": {"out", "sent"},
    "google_sheets_append_row": {"out", "success", "row"},
    "linear_create_issue": {"out", "id", "issueKey", "title"},
    "github_create_issue": {"out", "id", "number", "url"},
    "salesforce_create_lead": {"out", "id", "success"},
    "pagerduty_trigger_incident": {"out", "incidentId", "status"},
    "jira": {"out", "key", "id", "url"},
    "notion_create_page": {"out", "id", "url"},
    "s3_upload": {"out", "etag", "key", "url"},
    # Reliability
    "execution_monitor": {"out", "status", "durationMs"},
    "health_check": {"healthy", "unhealthy", "checks"},
    "sla_monitor": {"inSla", "breached", "successRate", "latencyMs"},
    "anomaly_detector": {"out", "anomalous", "score"},
    "incident_creator": {"out", "incidentId", "severity"},
    "escalation": {"out", "level", "escalated"},
}


def get_known_output_fields(node_type: str) -> set[str]:
    """Return the set of recognized output fields for a given node type."""
    return STANDARD_NODE_OUTPUT_FIELDS.get(node_type, {"out", "output", "body", "result", "data"})


def catalog_prompt() -> str:
    """Render the catalogue as a concise spec for the generation system prompt."""
    lines: list[str] = []
    # Organize by category for clarity
    categories: dict[str, list[dict[str, Any]]] = {}
    for node in NODE_CATALOG:
        cat = node.get("category", "ACTION")
        categories.setdefault(cat, []).append(node)

    for cat_name, nodes in categories.items():
        lines.append(f"\n--- {cat_name} NODES ---")
        for node in nodes:
            role = "TRIGGER" if node["trigger"] else "STEP"
            lines.append(f"- {node['type']} [{role}] — {node['purpose']}")
            lines.append(f"    outputs: {', '.join(node['outputs'])}")
            if node["config"]:
                for key, desc in node["config"].items():
                    lines.append(f"    config.{key}: {desc}")
            else:
                lines.append("    config: (none)")
    return "\n".join(lines)
